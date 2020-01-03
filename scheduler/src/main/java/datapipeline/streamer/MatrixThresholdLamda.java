package datapipeline.streamer;

import com.google.gson.Gson;
import datapipeline.KafkaConstants;
import datapipeline.KafkaManager;
import datapipeline.common.Tuple2;
import datapipeline.common.Tuple2Serde;
import datapipeline.processors.ControlledFilterTransformer;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.WindowStore;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;


public class MatrixThresholdLamda implements Runnable {

    private int _ID;
    private int _threshold;
    private String _topic;
    private int _window;

    public MatrixThresholdLamda(int ID, int threshold, String topic, int window) {
        _ID = ID;
        _threshold = threshold;
        _topic = topic;
        _window = window;
    }

    public void run() {

        final Properties streamsConfiguration = new Properties();
        final Serde<String> stringSerde = Serdes.String();

        // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
        // against which the application is run.
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, KafkaConstants.STREAMER_PREFIX + _ID);
        streamsConfiguration.put(StreamsConfig.CLIENT_ID_CONFIG, KafkaConstants.STREAMER_PREFIX + _ID);

        KafkaManager.deleteTopicWithPrefix(KafkaConstants.STREAMER_PREFIX + _ID);

        // Where to find Kafka broker(s).
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaConstants.KAFKA_BROKERS);

        // Specify default (de)serializers for record keys and for record values.
        streamsConfiguration.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName()); // Set the commit interval to 500ms so that any changes are flushed frequently. The low latency

        // ***very important***
        //streamsConfiguration.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);
        streamsConfiguration.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0);


        final StreamsBuilder builder = new StreamsBuilder();


        final KStream<String, String> views = builder.stream(_topic);
        ControlledFilterWrapper<String, String> wrapper = new ControlledFilterWrapper<>();

        KStream<String, String> mappedStream = views
                .flatMap((k, v) -> {
                    List<KeyValue<String, String>> result = new LinkedList<>();

                    Gson gson = new Gson();
                    int[] value = gson.fromJson(v, int[].class);

                    for (int i = 0; i < value.length; i++) {
                        result.add(new KeyValue<>(Integer.toString(i), Integer.toString(value[i])));
                            // KafkaManager.createTopic(job_id, this._ID,(short)1);
                    }
                    return result;
                });

        KGroupedStream<String, String> groupedStream = mappedStream.groupByKey(Grouped.with(stringSerde, stringSerde));
        TimeWindowedKStream <String, String> windowedStream = groupedStream.windowedBy(TimeWindows.of(Duration.ofMillis(_window)).grace(Duration.ofMillis((long)(_window * 0.1))));
        KTable<Windowed<String>, Tuple2<Double, Double>> aggregatedStream = windowedStream
                .aggregate(
                        () -> new Tuple2<>(0.0, 0.0),
                        (aggKey, newValue, aggregate)  -> {
                            aggregate.value1 ++;
                            try{
                                aggregate.value2 += Integer.parseInt(newValue);
                            } catch(Exception e) {

                            }
                            return aggregate;
                        },
                        Materialized.<String, Tuple2<Double, Double>, WindowStore<Bytes, byte[]>>as("timed-window").withValueSerde(new Tuple2Serde<>())
                )
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

        KStream<String, String> anomalousData = aggregatedStream.mapValues(value -> Double.toString(value.value2 / value.value1))
                //.filter((key, value) -> Double.parseDouble(value) > (double)_threshold)
                .toStream()
                // .filter((key, value) ->  value != null)
                .map(((key, value) -> KeyValue.pair(key.toString(), value)));



        wrapper.getControlledStream(anomalousData
                , builder
                , KafkaConstants.CONTROL_FLOW_TOPIC_PREFIX + _ID
                , new ControlledFilterTransformer<>(_ID, _threshold)
                ,_ID)
                .to(KafkaConstants.STREAMER_OUTPUT_TOPIC_PREFIX + _ID);

        final KafkaStreams streams = new KafkaStreams(builder.build(), streamsConfiguration);


        // Always (and unconditionally) clean local state prior to starting the processing topology.
        // We opt for this unconditional call here because this will make it easier for you to play around with the example
        // when resetting the application for doing a re-run (via the Application Reset Tool,
        // http://docs.confluent.io/current/streams/developer-guide.html#application-reset-tool).
        //
        // The drawback of cleaning up local state prior is that your app must rebuilt its local state from scratch, which
        // will take time and will require reading all the state-relevant data from the Kafka cluster over the network.
        // Thus in a production scenario you typically do not want to clean up always as we do here but rather only when it
        // is truly needed, i.e., only under certain conditions (e.g., the presence of a command line flag for your app).
        // See `ApplicationResetExample.java` for a production-like example.
        streams.cleanUp();
        streams.start();

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

    }
}
