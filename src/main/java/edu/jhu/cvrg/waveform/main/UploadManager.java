package edu.jhu.cvrg.waveform.main;
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

* @author Chris Jurado, Mike Shipway, Brandon Benitez, Andre Vilardo

* 
*/


import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.faces.context.FacesContext;
import javax.portlet.PortletSession;

import org.apache.axiom.om.OMElement;
import org.apache.log4j.Logger;

import com.liferay.portal.model.User;
import com.liferay.portal.security.auth.PrincipalThreadLocal;
import com.liferay.portal.security.permission.PermissionChecker;
import com.liferay.portal.security.permission.PermissionCheckerFactoryUtil;
import com.liferay.portal.security.permission.PermissionThreadLocal;
import com.liferay.portal.service.UserLocalServiceUtil;

import edu.jhu.cvrg.data.dto.UploadStatusDTO;
import edu.jhu.cvrg.data.enums.FileExtension;
import edu.jhu.cvrg.data.enums.FileType;
import edu.jhu.cvrg.data.enums.UploadState;
import edu.jhu.cvrg.data.factory.Connection;
import edu.jhu.cvrg.data.factory.ConnectionFactory;
import edu.jhu.cvrg.data.util.DataStorageException;
import edu.jhu.cvrg.filestore.enums.EnumFileExtension;
import edu.jhu.cvrg.filestore.exception.FSException;
import edu.jhu.cvrg.filestore.main.FileStoreFactory;
import edu.jhu.cvrg.filestore.main.FileStorer;
import edu.jhu.cvrg.filestore.model.FSFile;
import edu.jhu.cvrg.filestore.model.FSFolder;
import edu.jhu.cvrg.waveform.exception.DataExtractException;
import edu.jhu.cvrg.waveform.exception.UploadFailureException;
import edu.jhu.cvrg.waveform.model.ECGFileMeta;
import edu.jhu.cvrg.waveform.model.FileTreeNode;
import edu.jhu.cvrg.waveform.model.LocalFileTree;
import edu.jhu.cvrg.waveform.utility.ECGUploadProcessor;
import edu.jhu.cvrg.waveform.utility.ResourceUtility;
import edu.jhu.cvrg.waveform.utility.Semaphore;
import edu.jhu.cvrg.waveform.utility.WebServiceUtility;
import edu.jhu.icm.enums.DataFileFormat;

public class UploadManager extends Thread{

	public static String conversionStrategy = "Portlet";
	
	private Long validationTime; 
	private EnumFileExtension fileExtension;
	private UploadStatusDTO uploadStatusDTO;
	private Logger log = Logger.getLogger(UploadManager.class);
	private ECGFileMeta ecgFile = null;
	
	private long userId;
	private long companyId;
	private long groupId;
	private FileStorer fileStorer = null;
	
	private long virtualNodeId;
	
	@Override
	public void run() {
		Connection db = null;
		try {
			db = ConnectionFactory.createConnection();
			this.performFileExtraction(db);
		} catch (Exception e) {
			if(db!=null){
				if(uploadStatusDTO.getDocumentRecordId() != null){
					try {
						db.updateUploadStatus(uploadStatusDTO.getDocumentRecordId(), null, null, Boolean.FALSE, e.getMessage());
					} catch (DataStorageException e1) {
						log.error("Error on update the upload status." + e1.getMessage());
					}
				}else{
					uploadStatusDTO.setStatus(Boolean.FALSE);
					uploadStatusDTO.setMessage(e.getMessage());
				}
			}else{
				log.error("Exception is:", e);		
			}
			
			try{
				if(ecgFile != null && ecgFile.getFile() != null){
					this.getFileStorer().deleteFile(ecgFile.getFile().getId());
					if(ecgFile.getAuxiliarFiles() != null){
						for (FileExtension extKey : ecgFile.getAuxiliarFiles().keySet()) {
							this.getFileStorer().deleteFile(ecgFile.getAuxiliarFiles().get(extKey).getId());
						}
					}
				}
			} catch (FSException e1){
				log.error("Error on cleanup error process." + e1.getMessage());
			}
		}
	}
	
	public UploadStatusDTO storeUploadedFile(ECGFileMeta ecgFile, long folderUuid) throws UploadFailureException {
	
		validationTime = java.lang.System.currentTimeMillis();
		
		fileExtension = EnumFileExtension.valueOf(extension(ecgFile.getFile().getName()).toUpperCase());
		
		userId = ResourceUtility.getCurrentUserId();
		companyId = ResourceUtility.getCurrentCompanyId();
		groupId = ResourceUtility.getCurrentGroupId();
		
		String message = null;
		boolean performConvesion = true;
		boolean isWFDB = false;
		
		
		if (EnumFileExtension.XML.equals(fileExtension)) {
			log.info("Processing XML file.");
			processXMLFileExtension(ecgFile, folderUuid);
		}

		if (EnumFileExtension.HEA.equals(fileExtension)) {
			log.info("Checking HEA file.");
			checkHeaFile(ecgFile);
			isWFDB = true;
		}

		if (EnumFileExtension.DAT.equals(fileExtension)) {
			log.info("Checking DAT file.");
			checkBinaryFile(ecgFile);
			isWFDB = true;
		}
		
		if (EnumFileExtension.XYZ.equals(fileExtension)) {
			log.info("Checking XYZ file.");
			checkBinaryFile(ecgFile);
			isWFDB = true;
		}

		
		if (isWFDB) {
			try {
				Semaphore s = Semaphore.getCreateUploadSemaphore();
				s.take();

				ecgFile.setFileType(FileType.WFDB);
				this.saveFile(ecgFile, folderUuid, this.getFileStorer());
				message = this.checkWFDBFiles(ecgFile, fileExtension, this.getFileStorer());
				
				performConvesion = (message == null);
				
				s.release();
				
			} catch (FSException e) {
				log.error("Exception is:", e);
			} catch (InterruptedException e) {
				log.error("Exception is:", e);
			}
		}
		
		if (performConvesion) {
			
			int auxiliarFilesCount = ecgFile.getAuxiliarFiles() != null ? ecgFile.getAuxiliarFiles().values().size() : 0;
			long[] filesId = new long[1+auxiliarFilesCount];
			filesId[0] = ecgFile.getFile().getId();
			if(ecgFile.getAuxiliarFiles() != null){
				for (int i = 0; i <  ecgFile.getAuxiliarFiles().values().size(); i++) {
					FSFile auxFile = ecgFile.getAuxiliarFiles().values().iterator().next();
					filesId[i+1] = auxFile.getId();				
				}
			}
			
			try {
				Connection db = ConnectionFactory.createConnection();
				
				Long docId = db.initalDocumentStore(userId, ecgFile.getRecordName(), ecgFile.getSubjectID(), ecgFile.getFileType().ordinal(), ecgFile.getTreePath(), new GregorianCalendar(), filesId);
				ecgFile.setDocumentId(docId);
				
				this.ecgFile = ecgFile;
				validationTime = java.lang.System.currentTimeMillis() - validationTime;
				
				uploadStatusDTO = new UploadStatusDTO(docId, null, null, validationTime, null, null, null);
				uploadStatusDTO.setRecordName(ecgFile.getSubjectID());
				
				db.storeUploadStatus(uploadStatusDTO);
				
			} catch (DataStorageException e) {
				message = e.getMessage();
				validationTime = null;
			}finally{
				
			}
			
		} else {
			validationTime = null;
			if(message != null){
				message = "Incomplete ECG, waiting file(s) (" + message + ").";
			}
		}

		if(uploadStatusDTO == null){
			uploadStatusDTO = new UploadStatusDTO(null, null, null, validationTime,	null, null, message);
			uploadStatusDTO.setRecordName(ecgFile.getSubjectID());
		}
		
		return uploadStatusDTO;
	}
		
	
	private void processXMLFileExtension(ECGFileMeta ecgFile, long folderUuid){
		try {
			SniffedXmlInputStream xmlSniffer = new SniffedXmlInputStream(ecgFile.getFile().getFileDataAsInputStream());
			
			String encoding = xmlSniffer.getXmlEncoding();
		
			byte[] bytes = ecgFile.getFile().getFileDataAsBytes();
		
			StringBuilder xmlString = new StringBuilder(new String(bytes, encoding));
			
			// check for the first xml tag
 			// if it does not exist, remake the file using UTF-16
			// This section is done primarily in the case of Philips files, since their encoding is listed
			// as "utf-16" instead of "UTF-16"
				
			// This may need to be revisited in the future if other formatting issues like this crop up
 			if(xmlString.indexOf("xml") == -1) {
 				xmlString = new StringBuilder(new String(bytes, "UTF-16"));
 					
 				if(xmlString.indexOf("xml") == -1) {
 					if(xmlSniffer != null) {
						xmlSniffer.close();
					}
 					throw new UploadFailureException("Unexpected file.");
 				}
 				
			}
				
			// indicates one of the Philips formats
				
			// JDOM seems to be having problems building the XML structure for Philips 1.03,
			// Checking for the elements directly in the string is required to get the right version
			// and also takes less memory
			
			if(xmlString.indexOf("restingecgdata") != -1) {				
				if(xmlString.indexOf("<documentversion>1.03</documentversion>") != -1) {
					ecgFile.setFileType(FileType.PHILIPS_103);
				}
				else if(xmlString.indexOf("<documentversion>1.04</documentversion>") != -1) {
					ecgFile.setFileType(FileType.PHILIPS_104);
				}
				else {
					if(xmlSniffer != null) {
						xmlSniffer.close();
					}
					throw new UploadFailureException("Unrecognized version number for Philips file");
				}
			
			// indicates GE Muse 7
			}else if(xmlString.indexOf("RestingECG") != -1) {
				ecgFile.setFileType(FileType.MUSE_XML);
				
			// indicates Schiller 
			}else if(xmlString.indexOf("examdescript") != -1) {
				ecgFile.setFileType(FileType.SCHILLER);
			
			}else{
				ecgFile.setFileType(FileType.HL7);
			}
				
			this.saveFile(ecgFile, folderUuid, this.getFileStorer());
			
			if(xmlSniffer != null) {
				xmlSniffer.close();
			}
		} catch (IOException e) {
			log.error("Exception is:", e);
		} catch (UploadFailureException e) {
			log.error("Exception is:", e);
		}
	}
			

	private void checkBinaryFile(ECGFileMeta ecgFile) throws UploadFailureException {
		boolean isBinary = false;
		byte[] fileBytes = ecgFile.getFile().getFileDataAsBytes();
		int bytesToAnalyze = Double.valueOf(fileBytes.length*0.3).intValue();
		int encodeErrorLimit = Double.valueOf(bytesToAnalyze*0.2).intValue();
		int encodeErrorCount = 0;
		
		for (int j = 0; j < bytesToAnalyze; j++) {
			
			int c = (int) fileBytes[j]; 
			if(c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 126) ){
				isBinary = false;
			} else {
				isBinary = true;
				if(encodeErrorCount == encodeErrorLimit ){
					break;	
				}else{
					encodeErrorCount++;
				}
			}
		}
		
		if (!isBinary) {
			encodeErrorCount = 0;
			
			try {
				String s = new String(fileBytes, "UTF-16");
				for (int j = 0; j < s.length(); j++) {
					int c = (int) (s.charAt(j)); 
					if(c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 126) ){
						isBinary =  false;
					} else {
						isBinary = true;
						if(encodeErrorCount == encodeErrorLimit ){
							break;	
						}else{
							encodeErrorCount++;
						}
					}
				}
			} catch (UnsupportedEncodingException e) {
				isBinary = true;
				log.error("Exception is:", e);
			}
		}
		
		if(!isBinary){
			throw new UploadFailureException("Unexpected file.");
		}
	}

	private void checkHeaFile(ECGFileMeta ecgFile) throws UploadFailureException {
		boolean valid = false;
		
		StringBuilder line = new StringBuilder();
		int lineNumber = 0;
		int leadTotal = 0;
		int leadOnFile = 0;
		
		for (int i = 0; i < ecgFile.getFile().getFileSize(); i++) {
			
			char s = (char)ecgFile.getFile().getFileDataAsBytes()[i];
			
			line.append(s);
			
			if(s == '\n'){
				
				if(lineNumber > 0 && leadTotal == leadOnFile){
					break;
				}
				
				String[] headerInfo = line.toString().split(" ");
				
				valid = (headerInfo != null && headerInfo.length >= 2);
				if(!valid) break;
				
				if(lineNumber == 0){
					String fileRecordName = headerInfo[0];
					int index = fileRecordName.lastIndexOf('/');
					if(index != -1){
						fileRecordName = fileRecordName.substring(0, index);
					}
					
					valid = (ecgFile.getRecordName().equals(fileRecordName));
					if(!valid) break;

					leadTotal = Integer.valueOf(headerInfo[1]);
					
				}else if(lineNumber > 0 && valid){
					leadOnFile++;
				}
				
				line.delete(0, line.length()-1);
				lineNumber++;
			}
		}
		if(valid){
			valid = leadTotal == leadOnFile;
		}
		
		if(!valid){
			throw new UploadFailureException("Unexpected file.");
		}
		
	}

	private void saveFile(ECGFileMeta ecgFile, long folderUuid, FileStorer fileStorer) throws UploadFailureException {
		
		try {
			
			FSFolder newRecordFolder = fileStorer.addFolder(folderUuid, ecgFile.getRecordName(), ecgFile.isVirtual());
			
			FSFile newFile = fileStorer.addFile(newRecordFolder.getId(), ecgFile.getFile().getName(), ecgFile.getFile().getFileDataAsBytes(), ecgFile.isVirtual());
			
			ecgFile.setFile(newFile);
			
		} catch (FSException e) {
			e.printStackTrace();
			throw new UploadFailureException(e);
		} catch (Exception e) {
			e.printStackTrace();
			throw new UploadFailureException(e);
		}
	}

	private FileStorer getFileStorer() {
		if(fileStorer == null){
			String[] args = {String.valueOf(ResourceUtility.getCurrentGroupId()), String.valueOf(ResourceUtility.getCurrentUserId()), String.valueOf(ResourceUtility.getCurrentCompanyId())};
			fileStorer = FileStoreFactory.returnFileStore(ResourceUtility.getFileStorageType(), args);	
		}
		return fileStorer;
	}

	private String checkWFDBFiles(ECGFileMeta ecgFile, EnumFileExtension fileExtension, FileStorer fileStorer) throws FSException {
		
		String fileNameToFind = ecgFile.getRecordName();
		FSFile aux1 = null;
		FSFile aux2 = null;
		boolean haveThreeFiles = false;
		
		String ext1 = null;
		String ext2 = null;
		String message = null;
		
		if(FileExtension.HEA.equals(fileExtension)){
			ext1 = ".dat";
			aux1 = fileStorer.getFileByNameAndFolder(ecgFile.getFile().getParentId(), fileNameToFind + ext1, false);
			ecgFile.addAuxFile(FileExtension.DAT, aux1);
			
			haveThreeFiles = hasXYZ(ecgFile.getFile());
			
			if(haveThreeFiles){
				ext2 = ".xyz";
				aux2 = fileStorer.getFileByNameAndFolder(ecgFile.getFile().getParentId(), fileNameToFind + ext2, false);
				ecgFile.addAuxFile(FileExtension.XYZ, aux2);
			}
			
		}else if(FileExtension.DAT.equals(fileExtension)){
			ext1 = ".hea";
			aux1 = fileStorer.getFileByNameAndFolder(ecgFile.getFile().getParentId(), fileNameToFind + ext1, false);
			ecgFile.addAuxFile(FileExtension.HEA, aux1);
			
			haveThreeFiles = aux1 != null && hasXYZ(aux1);
			
			if(haveThreeFiles){
				ext2 = ".xyz";
				aux2 = fileStorer.getFileByNameAndFolder(ecgFile.getFile().getParentId(), fileNameToFind + ext2, false);
				ecgFile.addAuxFile(FileExtension.XYZ, aux2);
			}
			
		}else if(FileExtension.XYZ.equals(fileExtension)){
			ext1 = ".hea";
			haveThreeFiles = true;
			aux1 = fileStorer.getFileByNameAndFolder(ecgFile.getFile().getParentId(), fileNameToFind + ext1, false);
			ecgFile.addAuxFile(FileExtension.HEA, aux1);
			
			ext2 = ".dat";
			aux2 = fileStorer.getFileByNameAndFolder(ecgFile.getFile().getParentId(), fileNameToFind + ext2, false);
			ecgFile.addAuxFile(FileExtension.DAT, aux2);
		}
		
		
		if(aux1 == null){
			message = ext1;
		}
		if(haveThreeFiles){
			if(aux2 == null){
				if(aux1 == null){
					message += (" and " + ext2);
				}else{
					message = ext2;	
				}
			}
		}
		
		return message;
	}

	private static boolean hasXYZ(FSFile ecgFile) throws FSException{
		try {
			boolean hasXYZ = false;
			
			if(ecgFile != null){
				BufferedReader reader = new BufferedReader( new InputStreamReader(new ByteArrayInputStream(ecgFile.getFileDataAsBytes())));
				
				String line = null;
				while((line = reader.readLine()) != null){
					
					String[] headerInfo = line.toString().split(" ");
					
					if(headerInfo != null){
						String fileRecordName = headerInfo[0];
						hasXYZ = fileRecordName.contains(".xyz");
						if(hasXYZ){
							break;
						}
					}
				}
			}
			
			return hasXYZ;
		} catch (IOException e) {
			throw new FSException("Unable to read header file", e);
		}
	}

	private void extractDataByPortlet()  throws UploadFailureException {
		
		try {
			
			ECGUploadProcessor processor = new ECGUploadProcessor();
			processor.execute(ecgFile);
			
		} catch (DataExtractException e) {
			throw new UploadFailureException(e.getMessage(), e);
		}
		
	}
	
	private void extractDataByWS()  throws UploadFailureException {
		
		initializeLiferayPermissionChecker(userId);
		
		if(ResourceUtility.getNodeConversionService().equals("0")){
			log.error("Missing Web Service Configuration.  Cannot run File Conversion Web Service.");
			throw new UploadFailureException("Cannot run File Conversion Web Service. Missing Web Service Configuration.");
		}

		String method = "extractData";
		
		log.info("method = " + method);
		
		LinkedHashMap<String, String> parameterMap = new LinkedHashMap<String, String>();
	
		parameterMap.put("userId", 		String.valueOf(userId));
		parameterMap.put("documentId", 	String.valueOf(ecgFile.getDocumentId()));
		parameterMap.put("recordName", 	ecgFile.getRecordName());
		parameterMap.put("subjectId", 	ecgFile.getRecordName());
		parameterMap.put("studyID", 	ecgFile.getRecordName());
		parameterMap.put("datatype", 	ecgFile.getDatatype());
		
		parameterMap.put("filename", 	ecgFile.getFile().getName());
		parameterMap.put("treePath", 	ecgFile.getTreePath());
		parameterMap.put("fileSize", 	String.valueOf(ecgFile.getFile().getFileSize()));
		parameterMap.put("verbose", 	String.valueOf(false));
		parameterMap.put("service", 	"DataConversion");
		
		parameterMap.put("companyId", 	String.valueOf(companyId));
		parameterMap.put("groupId", 	String.valueOf(groupId));
		parameterMap.put("folderId", 	String.valueOf(ecgFile.getFile().getParentId()));
		
		//ENUMS FILETYPE AND DATAFILEFORMAT SHOULD BE SYNCRONIZED
		parameterMap.put("inputFormat",  String.valueOf(ecgFile.getFileType().ordinal()));
		parameterMap.put("outputFormat", String.valueOf(DataFileFormat.WFDB_16.ordinal()));
		
		LinkedHashMap<String, FSFile> filesMap = new LinkedHashMap<String, FSFile>();
		
		switch (fileExtension) {
		case HEA:
			filesMap.put("contentFile", ecgFile.getAuxiliarFiles().get(EnumFileExtension.DAT));
			filesMap.put("headerFile", ecgFile.getFile());
			if(ecgFile.getAuxiliarFiles().size() > 1){
				filesMap.put("extraFile", ecgFile.getAuxiliarFiles().get(EnumFileExtension.XYZ));
			}
			break;
		case DAT:
			filesMap.put("contentFile", ecgFile.getFile());
			filesMap.put("headerFile", ecgFile.getAuxiliarFiles().get(EnumFileExtension.HEA));
			if(ecgFile.getAuxiliarFiles().size() > 1){
				filesMap.put("extraFile", ecgFile.getAuxiliarFiles().get(EnumFileExtension.XYZ));
			}
			break;
		case XYZ:
			filesMap.put("extraFile", ecgFile.getFile());
			filesMap.put("contentFile", ecgFile.getAuxiliarFiles().get(EnumFileExtension.DAT));
			filesMap.put("headerFile", ecgFile.getAuxiliarFiles().get(EnumFileExtension.HEA));
			break;
		default:
			filesMap.put("contentFile", ecgFile.getFile());
			break;
		}
		

		log.info("Calling Web Service with " + ecgFile.getFile().getName() + ".");
		
		OMElement result = WebServiceUtility.callWebService(parameterMap, false, method, ResourceUtility.getNodeConversionService(), null, filesMap);
		
		if(result == null){
			throw new UploadFailureException("Webservice return is null.");
		}
		
		Map<String, OMElement> params = WebServiceUtility.extractParams(result);
		
		if(params != null){
			if(params.get("documentId") != null && params.get("documentId").getText() != null){
				Long.valueOf(params.get("documentId").getText());
			}else if(params.get("errorMessage").getText() != null && !params.get("errorMessage").getText().isEmpty()){
				throw new UploadFailureException(params.get("errorMessage").getText());
			}
		}
	}
	
	public void performFileExtraction(Connection db) throws UploadFailureException {
		
		if(ecgFile.getFileType() != null){
			Long docId = ecgFile.getDocumentId();
			
			long conversionTime = java.lang.System.currentTimeMillis();
			
			boolean useWS = !"Portlet".equals(conversionStrategy);
			
			if(useWS){
				extractDataByWS();
			}else{
				extractDataByPortlet();
			}
			
			conversionTime = java.lang.System.currentTimeMillis() - conversionTime;
			
			if(docId != null){
					
				log.info("["+docId+"]The runtime file validation is = " + validationTime + " milliseconds");
				log.info("["+docId+"]The runtime for WS tranfer, read and store the document on database is = " + conversionTime + " milliseconds");
				
				uploadStatusDTO.setDocumentRecordId(docId);
				
				try {
					if(ecgFile.isVirtual()){
						db.storeVirtualDocument(userId, docId, virtualNodeId, ecgFile.getRecordName());
						db.updateVirtualDocumentReferences(docId, ecgFile.getRecordName());
					}
				} catch (DataStorageException e) {
					throw new UploadFailureException("Unable to persist the shared document record.", e);
				}
			}else{
				throw new UploadFailureException("Conversion error");
			}
		}else{
			throw new UploadFailureException("Unidentified file format/type.");
		}
	}
	

	// TODO: make this into a function which determines which kind of text file
	// this is, and returns the correct method to use.
	private String evaluateTextFile(String fName) {
		String method = "geMuse";

		return method;
	}
	
	private String extension(String filename){
		return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
	}
	
	
	private void initializeLiferayPermissionChecker(long userId) throws UploadFailureException {
		try{
			PrincipalThreadLocal.setName(userId);
			User user = UserLocalServiceUtil.getUserById(userId);
	        PermissionChecker permissionChecker = PermissionCheckerFactoryUtil.create(user);
	        PermissionThreadLocal.setPermissionChecker(permissionChecker);
	    }catch (Exception e){
			throw new UploadFailureException("Fail on permission checker initialization. [userId="+userId+"]", e);
		}
		
	}

	public void fileUpload(String fileName, long fileSize, InputStream fileStream, String studyID, String dataType, LocalFileTree fileTree){

		UploadStatusDTO status = null;
		String recordName = extractRecordName(fileName);

		try {
			if (!fileTree.fileExistsInFolder(fileName)) {
				// The new 5th parameter has been added for character encoding,
				// specifically for XML files. If null is passed in,
				// the function will use UTF-8 by default
				ECGFileMeta ecgFile = createECGFileWrapper(fileStream, fileName,studyID, dataType, recordName, recordName, fileSize, fileTree.getFolderPath(fileTree.getSelectedFolderUuid()), fileTree.getEurekaNode());

				long folderUuid = fileTree.getSelectedFolderUuid();
				if(ecgFile.isVirtual()){
					virtualNodeId = fileTree.getSelectedFolderUuid();
					folderUuid = fileTree.getEurekaNode().getUuid();
				}
				
				status = storeUploadedFile(ecgFile,	folderUuid);
				if (status != null && status.getMessage() == null) {
					this.start();
				}

			} else {
				throw new UploadFailureException("This file already exists.");

			}

		} catch (UploadFailureException ufe) {
			status = new UploadStatusDTO(null, null, null, null, null,Boolean.FALSE, fileName	+ " failed because:  " + ufe.getMessage());
			status.setRecordName(recordName);



		} catch (Exception ex) {
			ex.printStackTrace();
			status = new UploadStatusDTO(null, null, null, null, null, Boolean.FALSE, fileName + " failed to upload for unknown reasons");
			status.setRecordName(recordName);
		}
		this.addToBackgroundQueue(status);

	}
	
	public void addToBackgroundQueue(UploadStatusDTO dto) {
		if(dto!=null){
			if(UploadManager.getBackgroundQueue() == null){
				setBackgroundQueue(new ArrayList<UploadStatusDTO>());
			}
			
			int index = UploadManager.getBackgroundQueue().indexOf(dto);
			
			if(index != -1){
				UploadStatusDTO older = UploadManager.getBackgroundQueue().get(index);
				if(UploadState.WAIT.equals(older.getState()) || 
				   UploadState.ERROR.equals(older.getState())){
					UploadManager.getBackgroundQueue().remove(index);	
				}
			}
			
			UploadManager.getBackgroundQueue().add(dto);	
		}
	}
	
	@SuppressWarnings("unchecked")
	public static List<UploadStatusDTO> getBackgroundQueue() {
		PortletSession session = (PortletSession) FacesContext.getCurrentInstance().getExternalContext().getSession(false);
		return (List<UploadStatusDTO>) session.getAttribute("upload.backgroundQueue");

	}

	public static void setBackgroundQueue(List<UploadStatusDTO> backgroundQueue) {
		PortletSession session = (PortletSession) FacesContext.getCurrentInstance().getExternalContext().getSession(false);
		session.setAttribute("upload.backgroundQueue", backgroundQueue);
	}

	private ECGFileMeta createECGFileWrapper(InputStream file, String fileName, String studyID, String dataType, String subjectId, String recordName, long fileSize, String treePath, FileTreeNode eurekaNode) throws UploadFailureException{

    	byte[] fileBytes = new byte[(int)fileSize];

    	ECGFileMeta ecgFile = new ECGFileMeta(subjectId, recordName, dataType, studyID, ResourceUtility.getCurrentUserId());
    	
		try {
			file.read(fileBytes);
			FSFile fileToUpload = new FSFile(0L, fileName, this.getExtesion(fileName), 0L, fileBytes, fileSize);
			ecgFile.setFile(fileToUpload);
			ecgFile.setTreePath(treePath);
			
			if(treePath != null && eurekaNode != null && treePath.startsWith(eurekaNode.getData().toString())){
				ecgFile.setVirtual(true);
				ecgFile.setTreePath(eurekaNode.getData().toString());
			}
			
		} catch (IOException e) {
			log.error(e.getMessage());
			throw new UploadFailureException("This upload failed because a " + e.getClass() + " was thrown with the following message:  " + e.getMessage(), e);
		}
		return ecgFile;
    }
    
    private String extractRecordName(String fileName) {

		String recordName = "";
		int location = fileName.indexOf(".");
		if (location != -1) {
			recordName = fileName.substring(0, location);
		} else {
			recordName = fileName;
		}

		return recordName;
	}
    
    private String getExtesion(String fileName){
    	return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }
}
