package plugins.lcwot.introduction;

import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.PluginRespirator;

public class LCWoT_introduction implements FredPlugin, FredPluginThreadless {
	private Worker threadworker;
	
	@Override
	public void runPlugin(PluginRespirator pr) {
		this.threadworker = new Worker(pr);
		this.threadworker.start();
	}
	
	@Override
	public void terminate() {
		this.threadworker.terminate();
	}
}