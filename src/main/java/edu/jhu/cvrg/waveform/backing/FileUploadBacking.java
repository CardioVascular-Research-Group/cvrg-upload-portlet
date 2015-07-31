package edu.jhu.cvrg.waveform.backing;
/*
Copyright 2013 Johns Hopkins University Institute for Computational Medicine

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
/**
* @author Chris Jurado
* 
*/
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.faces.application.FacesMessage;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;
import javax.faces.context.FacesContext;
import javax.faces.event.ActionEvent;
import javax.portlet.PortletSession;

import org.primefaces.context.RequestContext;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.event.NodeSelectEvent;
import org.primefaces.model.TreeNode;
import org.primefaces.model.UploadedFile;

import com.liferay.portal.model.User;

import edu.jhu.cvrg.data.dto.UploadStatusDTO;
import edu.jhu.cvrg.data.enums.UploadState;
import edu.jhu.cvrg.data.factory.Connection;
import edu.jhu.cvrg.data.factory.ConnectionFactory;
import edu.jhu.cvrg.data.util.DataStorageException;
import edu.jhu.cvrg.waveform.main.UploadManager;
import edu.jhu.cvrg.waveform.model.FileTreeNode;
import edu.jhu.cvrg.waveform.model.LocalFileTree;
import edu.jhu.cvrg.waveform.utility.ResourceUtility;

@ManagedBean(name="fileUploadBacking")
@ViewScoped

public class FileUploadBacking extends BackingBean implements Serializable{
	
	private static final long serialVersionUID = -4715402539808469047L;
	
	private LocalFileTree fileTree;
	private User userModel;

	private List<FacesMessage> messages;
	
	@PostConstruct
	public void init() {
		userModel = ResourceUtility.getCurrentUser();
		if(fileTree == null && userModel != null){
			fileTree = new LocalFileTree(userModel.getUserId());
			loadBackgroundQueue();
		}
		messages = new ArrayList<FacesMessage>();
	}
	
    public void handleFileUpload(FileUploadEvent event) {
    	//TODO: Implement a means for the user to input the studyID and the datatype.
    	
    	if(fileTree.getSelectedNode() == null){
    		fileTree.setDefaultSelected();
    	}
    	
    	UploadedFile file = event.getFile();
		String fileName = file.getFileName();
		fileName = fileName.replaceAll(" ", "_");
		long fileSize = file.getSize();
		
		InputStream fileStream = null;
		try {
			fileStream = file.getInputstream();
		} catch (IOException e) {
			getLog().error("Exception is:", e);
		}
		UploadManager uploadManager = new UploadManager();
    	uploadManager.fileUpload(fileName, fileSize, fileStream, "Mesa", "Rhythm Strips", fileTree);
    	
    }
    
    public void onNodeSelect(NodeSelectEvent event) { 
    	getLog().info("Node selected... ID is " + fileTree.getSelectedNode().getUuid());
    }
    
    public LocalFileTree getFileTree() {
		return fileTree;
	}

	
    public void onComplete() {
    	fileTree.initialize(this.getUser().getUserId());
    	//fileTree.refresh();
    	messages.clear();
    }
    
    public List<String> getErrorList(){
		List<String> errorList = null;

    	List<UploadStatusDTO> backgroundQueue = this.getBackgroundQueue();
    	if(backgroundQueue!=null && !backgroundQueue.isEmpty()){
    		errorList = new ArrayList<String>();
			for (UploadStatusDTO u : backgroundQueue) {
				if(u != null){
	    			switch (u.getState()) {
						case ERROR: errorList.add("Error on "+u.getRecordName()+" : " + u.getMessage()); break;
						case WARN: errorList.add("Annotation extraction error on "+u.getRecordName()+" : " + u.getMessage()); break;
						case WAIT: errorList.add("Waiting! "+u.getRecordName()+" : " + u.getMessage()); break;
						default:break;
					}
				}
			}
    	}
		
		return errorList;
    }
    
    
    public String getSummary(){
    	int done = 0;
    	int error = 0;
    	List<UploadStatusDTO> backgroundQueue = this.getBackgroundQueue();
    	if(backgroundQueue!=null && !backgroundQueue.isEmpty()){
    		StringBuilder sb = new StringBuilder();
    		for (UploadStatusDTO u : backgroundQueue) {
    			if(u != null){
	    			switch (u.getState()) {
						case DONE: done++; break;
						case WARN: done++; break;
						case ERROR: error++; break;
						default:break;
					}
    			}
				
			}
    		sb.append("Summary ").append(backgroundQueue.size());
    		
    		if(backgroundQueue.size() > 1){
    			sb.append(" items [");
    		}else{
    			sb.append(" item [");
    		}
    		
    		sb.append(done).append(" done - ").append(error);
    		
    		if(error > 1){
    			sb.append(" fail(s)]");
    		}else{
    			sb.append(" fail]");
    		}
    		
    		return sb.toString();
    	}
    	
    	
    	return null;
    }
	
	public User getUser(){
		return userModel;
	}
	
	public void loadBackgroundQueue(){
		Set<Long> listenIds = null;
		
		List<UploadStatusDTO> backgroundQueue = this.getBackgroundQueue();
		
        if(backgroundQueue != null && !backgroundQueue.isEmpty()){
        	listenIds = new HashSet<Long>();
        	for (UploadStatusDTO s : backgroundQueue) {
				if(s != null && s.getDocumentRecordId() != null){
					listenIds.add(s.getDocumentRecordId());
				}
			}
        }
        if(listenIds != null && !listenIds.isEmpty()){
        	try {
				List<UploadStatusDTO> tmpBackgroundQueue  = ConnectionFactory.createConnection().getUploadStatusByUserAndDocId(userModel.getUserId(), listenIds);
				if(tmpBackgroundQueue != null){
					backgroundQueue.removeAll(tmpBackgroundQueue);
			        for (UploadStatusDTO s : tmpBackgroundQueue) {
						backgroundQueue.add(s);
					}
		        }else{
		        	backgroundQueue.clear();
		        }
        	} catch (DataStorageException e) {
				this.getLog().error("Error on load the background upload queue. " + e.getMessage());
			}
        }
        
        if(backgroundQueue != null){
        	boolean stopListening = false;
        	for (UploadStatusDTO u : backgroundQueue) {
        		stopListening = (UploadState.DONE.equals(u.getState()) || UploadState.ERROR.equals(u.getState()) );
        		if(!stopListening){
        			break;
        		}
			}
        	
        	if(stopListening){
        		RequestContext context = RequestContext.getCurrentInstance();
    			context.execute("stopListening();");
    			context.execute("totalFiles = " + backgroundQueue.size());
        	}
        }
        
	}

	public List<UploadStatusDTO> getBackgroundQueue(){
    	return UploadManager.getBackgroundQueue();
    }
    
	public void addToBackgroundQueue(UploadStatusDTO dto) {
		if(dto!=null){
			if(this.getBackgroundQueue() == null){
				setBackgroundQueue(new ArrayList<UploadStatusDTO>());
			}
			int index = this.getBackgroundQueue().indexOf(dto);
			if(index != -1){
				UploadStatusDTO older = this.getBackgroundQueue().get(index);
				if(UploadState.WAIT.equals(older.getState()) || 
				   UploadState.ERROR.equals(older.getState())){
					this.getBackgroundQueue().remove(index);	
				}
			}
			this.getBackgroundQueue().add(dto);	
		}
	}

	public void setBackgroundQueue(List<UploadStatusDTO> backgroundQueue) {
		PortletSession session = (PortletSession) FacesContext.getCurrentInstance().getExternalContext().getSession(false);
		session.setAttribute("upload.backgroundQueue", backgroundQueue);
	}
	
    public void removeTableItem(){
    	Map<String,String> params = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap();
        String index = params.get("index");
        
    	if(index != null ){
    		int indexTableToRemove = Integer.parseInt(index);
    		
    		List<UploadStatusDTO> backgroundQueue = this.getBackgroundQueue();
    		
    		if(indexTableToRemove >= 0 && (backgroundQueue != null && backgroundQueue.size() > indexTableToRemove)){
    			backgroundQueue.remove(indexTableToRemove);
    		}
    		
    		RequestContext context = RequestContext.getCurrentInstance();
			context.execute("removeFile();");
    	}
    }
    
    public void removeAllDoneItem(){
    	List<UploadStatusDTO> backgroundQueue = this.getBackgroundQueue();
    	
		if(backgroundQueue != null && !backgroundQueue.isEmpty()){
			
			List<UploadStatusDTO> toRemove = new ArrayList<UploadStatusDTO>();
			for (UploadStatusDTO dto : backgroundQueue) {
				if(UploadState.DONE.equals(dto.getState())){
					toRemove.add(dto);
				}
			}
			backgroundQueue.removeAll(toRemove);
			
			RequestContext context = RequestContext.getCurrentInstance();
			context.execute("totalFiles = " + backgroundQueue.size());
		}
	}
    
    public boolean isShowBackgroundPanel(){
    	return this.getBackgroundQueue() != null && !this.getBackgroundQueue().isEmpty(); 
    }
    
    public void deleteAction(ActionEvent event){
    	FileTreeNode node = fileTree.getSelectedNode();
    	
    	if(!node.equals(fileTree.getMySubjectsNode()) && !node.equals(fileTree.getEurekaNode())){
	    	try {
	    		Connection db = ConnectionFactory.createConnection();
	    		
	    		switch (node.getFileNode().getStoreStrategy()) {
				case LIFERAY_61:
					deleteSubNodes(node, db);
					break;
	
				case VIRTUAL_DB:
					
					long nodeId = node.getUuid();
					if(node.isDocument()){
						nodeId = node.getFileNode().getParent().getUuid();
					}
					
					db.deleteVirtualNode(this.getUser().getUserId(), nodeId);
					
					break;
				default:
					
					break;
				}
	    	} catch (DataStorageException e) {
				e.printStackTrace();
			}
	    	fileTree.deleteSelectedNode();
    	}
    	
    }

	private void deleteSubNodes(FileTreeNode node, Connection db)throws DataStorageException {
		if(node.isDocument()){
			db.deleteDocumentRecord(this.getUser().getUserId(), node.getDocumentRecordId());
		}else{
			List<TreeNode> children = node.getChildren();
			for (TreeNode subNode : children) {
				deleteSubNodes((FileTreeNode)subNode, db);	
			}
		}
	}

}
