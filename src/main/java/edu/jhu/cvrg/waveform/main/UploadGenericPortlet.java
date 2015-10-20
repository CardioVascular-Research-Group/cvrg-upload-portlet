package edu.jhu.cvrg.waveform.main;

import javax.portlet.PortletException;
import javax.portlet.faces.GenericFacesPortlet;

public class UploadGenericPortlet extends GenericFacesPortlet {

	@Override
	public void init() throws PortletException {
		super.init();
		DeleteDocumentThreadController tc = new DeleteDocumentThreadController();
		tc.start();
	}
}
