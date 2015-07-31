
	var totalFiles = 0;
	var progressTimer;
	
	function startListening(onLoad) {
		
		$('#uploader').fileupload({
            dropZone: $('.ui-fileupload-content')
         });
		
		if(onLoad == null){
			onLoad = false;
		}
		
		if(onLoad){
			var phaseColumns = $('td.queuePhaseColumn span');
			totalFiles = phaseColumns.length;
			
			var continueListening = check(phaseColumns);
        	
        	if(continueListening) {
        		startPooler();
        	}
        }else{
        	totalFiles+=$('table.ui-fileupload-files tr').length;
        	
        	startPooler();
			showBackgroundPanel();
		}
	    
    }  
  
	function startPooler(){
		if(progressTimer == null){
			progressTimer = setInterval(function() {  
	            
	        	var phaseColumns = $('td.queuePhaseColumn span');
	        	var activeCount = phaseColumns.length;
	        	
	        	var continueListening = check(phaseColumns);
	        	
	            if(continueListening) {
	            	showBackgroundPanel();
	            	loadBackgroundQueue();
	            }else{
	            	loadBackgroundQueue();
	            	totalFiles = activeCount; 
	            	stopListening();
	            }  
	            
	        }, 1000);  
		}
	}
	
	
	function check(phaseColumns){
		
		var continueListening = null;
		
		var waitCount = 0;
		for(var i = 0; i < phaseColumns.length; i++){
    		continueListening = !(phaseColumns[i].innerHTML == 'DONE' || phaseColumns[i].innerHTML == 'ERROR' || phaseColumns[i].innerHTML == 'WARN' || phaseColumns[i].innerHTML == 'WAIT');
    		
    		if(continueListening){
    			break;
    		}
    		
    		if(phaseColumns[i].innerHTML == 'WAIT'){
    			waitCount++;
    		}
    	}
		
		return ($('table.ui-fileupload-files tr').length > 0 || (phaseColumns.length) < (totalFiles)) || (continueListening != null && continueListening);
	}
	
    function stopListening() {
    	if(progressTimer != null){
    		clearInterval(progressTimer);
    		progressTimer = null;
    	}
        onComplete();
    }  
    
    
    function showBackgroundPanel(){
    	if($('div.ui-layout-resizer-east-closed').length > 0){
			$('.ui-layout-resizer-east .ui-layout-unit-expand-icon').click();	
		}
    }
    
    function removeFile(){
    	if(totalFiles > 0){
    		totalFiles--;
    	}
    	if(totalFiles == 0){
    		removeAll();
    	}
    }
    
    function removeAll(){
    	$('div.summary').remove();
    }