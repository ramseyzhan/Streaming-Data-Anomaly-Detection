$(document).ready( function() {
    let brandspin = $( "#brandspin" ).spinner();
    let productspin = $( "#productspin" ).spinner();
    let categoryspin = $( "#categoryspin" ).spinner();
    let cspin1 = $( "#customer1" ).spinner();
    let cspin2 = $( "#customer2" ).spinner();
    let cspin3 = $( "#customer3" ).spinner();
    $( "#rainthreshold" ).slider({
        range: "max",
        min: 6,
        max: 18,
        value: 1,
        slide: function( event, ui ) {
          $( "#currbar" ).val( ui.value );
        }
      });
    $( "#currbar" ).val( $( "#rainthreshold" ).slider( "value" ) );
    $('#submit1').click();
    $('#submit2').click();
    function customertable(){
      let rowTemplate = '<tr>' +
          '<td><%this.TimeStamp%> </td>' +
          '<td><%this.UserID%> </td>' +
          '<td><%this.AnomalyReason%> </td>' +
          '</tr>';
      $.ajax({
        type:"get",
        url:"/api/customer/",
        dataType:"json",
        success:function(result){
          $('#customertable').renderTable({
            template:rowTemplate,
            data:result,
            pagination:{
              rowPageCount:10
            }
          });
        },
        error: function(msg){
          console.log("Customer information loading failed!");
        }
      });
    };
    customertable();
    function otherstable(){
      let rowTemplate = '<tr>' +
          '<td><%this.TimeStamp%> </td>' +
          '<td><%this.Case%> </td>' +
          '<td><%this.ID%> </td>' +
          '</tr>';
      $.ajax({
        type:"get",
        url:"/api/otherstable/",
        dataType:"json",
        success:function(result){
          $('#otherstable').renderTable({
            template:rowTemplate,
            data:result,
            pagination:{
              rowPageCount:10
            }
          });
        },
        error: function(msg){
          console.log("Others information loading failed!");
        }
      });
    };
    otherstable();
} );
