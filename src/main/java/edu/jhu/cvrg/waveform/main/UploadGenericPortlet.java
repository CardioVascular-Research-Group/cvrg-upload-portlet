package edu.jhu.cvrg.waveform.main;

import javax.portlet.PortletException;
import javax.portlet.faces.GenericFacesPortlet;

/**
 * The generic portlet, used to initialize the delete document thread controller.
 * @author avilard4
 *
 */
public class UploadGenericPortlet extends GenericFacesPortlet {

	@Override
	public void init() throws PortletException {
		super.init();
		DeleteDocumentThreadController tc = new DeleteDocumentThreadController();
		tc.start();
	}
}
