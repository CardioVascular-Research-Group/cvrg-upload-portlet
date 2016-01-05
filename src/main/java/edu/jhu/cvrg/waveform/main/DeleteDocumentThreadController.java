package edu.jhu.cvrg.waveform.main;

import java.io.Serializable;
import java.util.List;

import edu.jhu.cvrg.data.dto.DocumentRecordDTO;
import edu.jhu.cvrg.data.factory.Connection;
import edu.jhu.cvrg.data.factory.ConnectionFactory;
import edu.jhu.cvrg.data.util.DataStorageException;
import edu.jhu.cvrg.waveform.utility.ResourceUtility;

/**
 * Class responsible manage the delete document threads, assuming the defined pool size and wait time.
 *  * 
 * @author avilard4
 *
 */
public class DeleteDocumentThreadController extends Thread implements Serializable{

	private static final long serialVersionUID = 8729599493399758784L;
	private Connection con;
	
	/**
	 * Maximum active delete threads
	 */
	private static int threadPoolSize = 5;
	/**
	 * Reduced wait time. Used when we have delete thread on queue
	 */
	private static int threadPoolSleepTime = 500;
	/**
	 * Manager wait time, used when we don't have any thread on queue.
	 */
	private static int threadRestTime = 5*60*1000;
	/**
	 * The thread group used to manage the active threads.
	 */
	private static ThreadGroup group = new ThreadGroup("DeleteDocumentGlobalGroup");
	
	public DeleteDocumentThreadController() {
		super("DeleteDocumentThreadController");
		try {
			con = ConnectionFactory.createConnection();
		} catch (DataStorageException e) {
			e.printStackTrace();
		}
	} 
	
	@Override
	public void run() {
		while(true){
			try {
				if(ResourceUtility.getOpenTsdbHost() != null){
					List<DocumentRecordDTO> docsToDelete = con.getDocumentsToDelete();
					for (DocumentRecordDTO documentRecordDTO : docsToDelete) {
						DeleteSubjectThread t = new DeleteSubjectThread(group, documentRecordDTO, con);
						t.start();
						while (group.activeCount() >= threadPoolSize) {
							DeleteDocumentThreadController.sleep(threadPoolSleepTime);
						}
					}
				}
				DeleteDocumentThreadController.sleep(threadRestTime);
			} catch (DataStorageException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	
	
	
}
