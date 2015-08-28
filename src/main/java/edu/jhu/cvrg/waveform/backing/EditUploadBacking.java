package edu.jhu.cvrg.waveform.backing;

import java.io.Serializable;

import javax.faces.application.FacesMessage;
import javax.faces.bean.ManagedBean;
import javax.faces.bean.ViewScoped;

import com.liferay.faces.portal.context.LiferayFacesContext;

import edu.jhu.cvrg.waveform.main.UploadManager;

@ManagedBean(name="editUploadBacking")
@ViewScoped
public class EditUploadBacking extends BackingBean implements Serializable{

	private static final long serialVersionUID = -1034721478645990912L;

	private String conversionStrategy = UploadManager.conversionStrategy;
	
	public String getConversionStrategy() {
		return conversionStrategy;
	}

	public void setConversionStrategy(String conversionStrategy) {
		this.conversionStrategy = conversionStrategy;
	}
	
	
	public String updateStrategy(){
		UploadManager.conversionStrategy = conversionStrategy;
		String summary = "Strategy " + UploadManager.conversionStrategy + " selected.";
        LiferayFacesContext.getCurrentInstance().addMessage(null, new FacesMessage(summary));
		return null;
	}
	
	
}
