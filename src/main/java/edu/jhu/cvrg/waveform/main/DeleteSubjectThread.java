package edu.jhu.cvrg.waveform.main;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;

import org.apache.log4j.Logger;

import edu.jhu.cvrg.data.dto.DocumentRecordDTO;
import edu.jhu.cvrg.data.factory.Connection;
import edu.jhu.cvrg.data.util.DataStorageException;
import edu.jhu.cvrg.timeseriesstore.exceptions.OpenTSDBException;
import edu.jhu.cvrg.timeseriesstore.opentsdb.TimeSeriesStorer;
import edu.jhu.cvrg.waveform.utility.ResourceUtility;

public class DeleteSubjectThread extends Thread{
	
	private static Logger log = Logger.getLogger(DeleteSubjectThread.class);
	
	private DocumentRecordDTO document;
	private Connection db;
	
	public DeleteSubjectThread(ThreadGroup group, DocumentRecordDTO document, Connection con) {
		super(group, "Delete|Doc_"+document.getDocumentRecordId()+"|User_"+document.getUserId());
		this.document = document;
		this.db = con;
	}
	
	@Override
	public void run() {
		try {
			this.deleteTimeSeries(document);
			db.deleteDocumentRecord(document.getUserId(), document.getDocumentRecordId());
		} catch (DataStorageException e) {
			log.error("Error on delete subject thread. Message: " +e.getMessage());
		} catch (OpenTSDBException e) {
			log.error("Error on delete subject thread. Message: " +e.getMessage());
		}
	}
	
	private void deleteTimeSeries(DocumentRecordDTO doc) throws OpenTSDBException {
		Calendar zeroTime = new GregorianCalendar(2015, Calendar.JANUARY, 1);
		zeroTime.setTimeZone(TimeZone.getTimeZone("US/Eastern"));
		
		final long zeroTimeInMillis = zeroTime.getTimeInMillis(); 
		long timeGapBetweenPoints = 1000L/Double.valueOf(doc.getSamplingRate()).longValue() * 1000;
		long endEpoch = zeroTimeInMillis + ( doc.getSamplesPerChannel() * timeGapBetweenPoints);
		
		HashMap<String, String> tags = new HashMap<String, String>();
		tags.put("timeseriesid", doc.getTimeSeriesId());
		
		String[] leadNames = doc.getLeadNames().split(",");
		List<String> metrics = new ArrayList<String>();
		
		for (int channel = 0; channel < leadNames.length; channel++) {
			String leadName = leadNames[channel];
			metrics.add("ecg."+leadName.trim()+".uv");		
		}

		TimeSeriesStorer.deleteTimeSeries(ResourceUtility.getOpenTsdbHost(), zeroTimeInMillis, endEpoch, metrics, tags, ResourceUtility.getOpenTsdbSshUser(), ResourceUtility.getOpenTsdbSshPassword());
	}
}
