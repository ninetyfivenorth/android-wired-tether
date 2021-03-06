/**
 *  This program is free software; you can redistribute it and/or modify it under 
 *  the terms of the GNU General Public License as published by the Free Software 
 *  Foundation; either version 3 of the License, or (at your option) any later 
 *  version.
 *  You should have received a copy of the GNU General Public License along with 
 *  this program; if not, see <http://www.gnu.org/licenses/>. 
 *  Use this application at your own risk.
 *
 *  Copyright (c) 2009 by Harald Mueller and Seth Lemons.
 */
 
package android.tether.usb;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Properties;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.tether.usb.data.ClientData;
import android.tether.usb.system.CoreTask;
import android.tether.usb.system.WebserviceTask;
import android.util.Log;
import android.widget.Toast;

public class TetherApplication extends Application {

	public static final String MSG_TAG = "TETHER -> TetherApplication";
	
	// StartUp-Check perfomed
	public boolean startupCheckPerformed = false;
	
	// Client-Connect-Thread
	private Thread clientConnectThread = null;
	private static final int CLIENT_CONNECT_AUTHORIZED = 1;
	
	// Data counters
	private Thread trafficCounterThread = null;

	// DNS-Server-Update Thread
	private Thread dnsUpdateThread = null;
	
	// WifiManager
	public String tetherNetworkDevice = "";
	
	// PowerManagement
	private PowerManager powerManager = null;
	private PowerManager.WakeLock wakeLock = null;

	// Preferences
	public SharedPreferences settings = null;
	public SharedPreferences.Editor preferenceEditor = null;
	
    // Notification
	public NotificationManager notificationManager;
	private Notification notification;
	
	// Intents
	private PendingIntent mainIntent;
	
	// CoreTask
	public CoreTask coretask = null;
	
	// WebserviceTask
	public WebserviceTask webserviceTask = null;
	
	// Client notification
	Notification clientConnectNotification =  null;
	String connectedMac = null;
	
	// Update Url
	private static final String APPLICATION_PROPERTIES_URL = "http://android-wired-tether.googlecode.com/svn/download/update/all/application.properties";
	private static final String APPLICATION_DOWNLOAD_URL = "http://android-wired-tether.googlecode.com/files/";
	
	@Override
	public void onCreate() {
		Log.d(MSG_TAG, "Calling onCreate()");
		
		//create CoreTask
		this.coretask = new CoreTask();
		this.coretask.setPath(this.getApplicationContext().getFilesDir().getParent());
		Log.d(MSG_TAG, "Current directory is "+this.getApplicationContext().getFilesDir().getParent());

		//create WebserviceTask
		this.webserviceTask = new WebserviceTask();
		
        // Check Homedir, or create it
        this.checkDirs(); 
        
        // Preferences
		this.settings = PreferenceManager.getDefaultSharedPreferences(this);
		
        // preferenceEditor
        this.preferenceEditor = settings.edit();

        // Powermanagement
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "TETHER_WAKE_LOCK");
		
        // init notificationManager
        this.notificationManager = (NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE);
    	this.notification = new Notification(R.drawable.start_notification, "Wired Tether", System.currentTimeMillis());
    	this.mainIntent = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
    	
    	// Client notification
 	   	this.clientConnectNotification = new Notification(R.drawable.connected, "Wired Tether", 0);
	}

	@Override
	public void onTerminate() {
		Log.d(MSG_TAG, "Calling onTerminate()");
    	// Stopping Tether
		this.stopTether();
		// Remove all notifications
		this.notificationManager.cancelAll();
	}

	// Start/Stop Tethering
    public int startTether() {
    	/*
    	 * ReturnCodes:
    	 *    0 = All OK, Service started
    	 *    1 = Mobile-Data-Connection not established (not used at the moment)
    	 *    2 = Fatal error 
    	 */
    	
    	/*boolean connected = this.mobileNetworkActivated();
        if (connected == false) {
        	// Go ahead even if there is no active mobile-network
        	 return 1;
        }*/
    	// Reset Client-Connect-Mac
    	this.connectedMac = null;
        
        // Update resolv.conf-file
        String dns[] = this.coretask.updateResolvConf();
        
    	// Starting service
        if (this.coretask.runRootCommand(this.coretask.DATA_FILE_PATH+"/bin/tether start")) {
        	
        	this.clientConnectEnable(true);
    		this.trafficCounterEnable(true);
    		this.dnsUpdateEnable(dns, true);
        	
			// Acquire Wakelock
			this.acquireWakeLock();
			
    		return 0;
    	}
    	return 2;
    }
    
    public boolean stopTether() {
    	this.releaseWakeLock();
    	this.clientConnectEnable(false);
    	boolean stopped = this.coretask.runRootCommand(this.coretask.DATA_FILE_PATH+"/bin/tether stop");
		this.notificationManager.cancelAll();
		this.trafficCounterEnable(false);
		this.dnsUpdateEnable(false);
		return stopped;
    }
	
    public boolean restartTether() {
    	return this.restartTether(0, 0);
    }
    
    public boolean restartTether(int tetherModeToStop, int tetherModeToStart) {
    	/* TetherModes:
    	 * 		0 =  wifi
    	 * 		1 =  bluetooth
    	 */
    	String command;
    	boolean stopped = false;
    	command = this.coretask.DATA_FILE_PATH+"/bin/tether stop";
		stopped = this.coretask.runRootCommand(command);    	
    	this.clientConnectEnable(false);
    	if (stopped != true) {
    		Log.d(MSG_TAG, "Couldn't stop tethering.");
    		return false;
    	}
    	command = this.coretask.DATA_FILE_PATH+"/bin/tether start";
    	if (this.coretask.runRootCommand(command)) {
    		this.clientConnectEnable(true);
    	}
    	else {
    		Log.d(MSG_TAG, "Couldn't stop tethering.");
    		return false;
    	}
    	return true;
    }
    
    // gets user preference on whether wakelock should be disabled during tethering
    public boolean isWakeLockDisabled(){
		return this.settings.getBoolean("wakelockpref", false);
	} 
	
    // gets user preference on whether sync should be disabled during tethering
    public boolean isSyncDisabled(){
		return this.settings.getBoolean("syncpref", false);
	}
    
    // gets user preference on whether sync should be disabled during tethering
    public boolean isUpdatecDisabled(){
		return this.settings.getBoolean("updatepref", false);
	}
    
    // get preferences on whether donate-dialog should be displayed
    public boolean showDonationDialog() {
    	return this.settings.getBoolean("donatepref", true);
    }

    // WakeLock
	public void releaseWakeLock() {
		try {
			if(this.wakeLock != null && this.wakeLock.isHeld()) {
				Log.d(MSG_TAG, "Trying to release WakeLock NOW!");
				this.wakeLock.release();
			}
		} catch (Exception ex) {
			Log.d(MSG_TAG, "Ups ... an exception happend while trying to release WakeLock - Here is what I know: "+ex.getMessage());
		}
	}
    
	public void acquireWakeLock() {
		try {
			if (this.isWakeLockDisabled() == false) {
				Log.d(MSG_TAG, "Trying to acquire WakeLock NOW!");
				this.wakeLock.acquire();
			}
		} catch (Exception ex) {
			Log.d(MSG_TAG, "Ups ... an exception happend while trying to acquire WakeLock - Here is what I know: "+ex.getMessage());
		}
	}
    
    // Notification
    public void showStartNotification() {
		notification.flags = Notification.FLAG_ONGOING_EVENT;
    	notification.setLatestEventInfo(this, "Wired Tether", "Tethering is currently running ...", this.mainIntent);
    	this.notificationManager.notify(-1, this.notification);
    }
    
    Handler clientConnectHandler = new Handler() {
 	   public void handleMessage(Message msg) {
 		   ClientData clientData = (ClientData)msg.obj;
 		   if (TetherApplication.this.connectedMac == null || TetherApplication.this.connectedMac.equals(clientData.getMacAddress()) == false) {
 			   TetherApplication.this.connectedMac = clientData.getMacAddress();
 			   TetherApplication.this.showClientConnectNotification(clientData);
 		   }
 	   }
    };
    
    public void showClientConnectNotification(ClientData clientData) {
		Log.d(MSG_TAG, "New client connected ==> "+clientData.getClientName()+" - "+clientData.getMacAddress());
 	   	clientConnectNotification.tickerText = clientData.getClientName()+" ("+clientData.getMacAddress()+")";
 	   	if (!this.settings.getString("notifyring", "").equals(""))
 	   		clientConnectNotification.sound = Uri.parse(this.settings.getString("notifyring", ""));

 	   	if(this.settings.getBoolean("notifyvibrate", true))
 	   		clientConnectNotification.vibrate = new long[] {100, 200, 100, 200};

 	   	clientConnectNotification.setLatestEventInfo(this, "Wired Tether", clientData.getClientName()+" ("+clientData.getMacAddress()+") connected ...", this.mainIntent);
 	   	clientConnectNotification.flags = Notification.FLAG_AUTO_CANCEL;
 	   	this.notificationManager.notify(1, clientConnectNotification);
    } 
    
    public void recoverConfig() {
    	// updating lan-settings
    	String lanconfig = this.settings.getString("lannetworkpref", "172.20.23.252/30");
    	this.coretask.writeLanConf(lanconfig);
    	
    	this.displayToastMessage("Configuration recovered.");
    }
    
    public boolean binariesExists() {
    	File file = new File(this.coretask.DATA_FILE_PATH+"/bin/tether");
    	return file.exists();
    }
    
    Handler displayMessageHandler = new Handler(){
        public void handleMessage(Message msg) {
       		if (msg.obj != null) {
       			TetherApplication.this.displayToastMessage((String)msg.obj);
       		}
        	super.handleMessage(msg);
        }
    };
  
    public void installFiles() {
    	new Thread(new Runnable(){
			public void run(){
				String message = null;
				// tether
		    	if (message == null) {
			    	message = TetherApplication.this.copyBinary(TetherApplication.this.coretask.DATA_FILE_PATH+"/bin/tether", R.raw.tether);
		    	}
				// iptables
		    	if (message == null) {
			    	message = TetherApplication.this.copyBinary(TetherApplication.this.coretask.DATA_FILE_PATH+"/bin/iptables", R.raw.iptables);
		    	}	
		    	// dnsmasq
		    	if (message == null) {
			    	message = TetherApplication.this.copyBinary(TetherApplication.this.coretask.DATA_FILE_PATH+"/bin/dnsmasq", R.raw.dnsmasq);
		    	}
		    	// ifconfig
		    	if (message == null) {
			    	message = TetherApplication.this.copyBinary(TetherApplication.this.coretask.DATA_FILE_PATH+"/bin/ifconfig", R.raw.ifconfig);
		    	}		    	
				try {
		    		TetherApplication.this.coretask.chmodBin();
				} catch (Exception e) {
					message = "Unable to change permission on binary files!";
				}
		    	// dnsmasq.conf
				if (message == null) {
					message = TetherApplication.this.copyBinary(TetherApplication.this.coretask.DATA_FILE_PATH+"/conf/dnsmasq.conf", R.raw.dnsmasq_conf);
					TetherApplication.this.coretask.updateDnsmasqFilepath();
				}
				// version
				if (message == null) {
					TetherApplication.this.copyBinary(TetherApplication.this.coretask.DATA_FILE_PATH+"/conf/version", R.raw.version);
				}				
				if (message == null) {
			    	message = "Binaries and config-files installed!";
				}
				
				// Removing ols lan-config-file
				File lanConfFile = new File(TetherApplication.this.coretask.DATA_FILE_PATH+"/conf/lan_network.conf");
				if (lanConfFile.exists()) {
					lanConfFile.delete();
				}
				
				// Sending message
				Message msg = new Message();
				msg.obj = message;
				TetherApplication.this.displayMessageHandler.sendMessage(msg);
			}
		}).start();
    }
    
    public void checkForUpdate() {
    	if (this.isUpdatecDisabled()) {
    		Log.d(MSG_TAG, "Update-checks are disabled!");	
    		return;
    	}
    	new Thread(new Runnable(){
			public void run(){
				Looper.prepare();
				// Getting Properties
				Properties updateProperties = TetherApplication.this.webserviceTask.queryForProperty(APPLICATION_PROPERTIES_URL);
				if (updateProperties != null && updateProperties.containsKey("versionCode") && updateProperties.containsKey("fileName")) {
					int availableVersion = Integer.parseInt(updateProperties.getProperty("versionCode"));
					int installedVersion = TetherApplication.this.getVersionNumber();
					String fileName = updateProperties.getProperty("fileName");
					if (availableVersion != installedVersion) {
						Log.d(MSG_TAG, "Installed version '"+installedVersion+"' and available version '"+availableVersion+"' do not match!");
						MainActivity.currentInstance.openUpdateDialog(APPLICATION_DOWNLOAD_URL+fileName, fileName);
					}
				}
				Looper.loop();
			}
    	}).start();
    }
   
    public void downloadUpdate(final String downloadFileUrl, final String fileName) {
    	new Thread(new Runnable(){
			public void run(){
				Message msg = Message.obtain();
            	msg.what = MainActivity.MESSAGE_DOWNLOAD_STARTING;
            	msg.obj = "Downloading update...";
            	MainActivity.currentInstance.viewUpdateHandler.sendMessage(msg);
				TetherApplication.this.webserviceTask.downloadUpdateFile(downloadFileUrl, fileName);
				Intent intent = new Intent(Intent.ACTION_VIEW); 
			    intent.setDataAndType(android.net.Uri.fromFile(new File(WebserviceTask.DOWNLOAD_FILEPATH+"/"+fileName)),"application/vnd.android.package-archive"); 
			    MainActivity.currentInstance.startActivity(intent);
			}
    	}).start();
    }
    
    private String copyBinary(String filename, int resource) {
    	File outFile = new File(filename);
    	Log.d(MSG_TAG, "Copying file '"+filename+"' ...");
    	InputStream is = this.getResources().openRawResource(resource);
    	byte buf[]=new byte[1024];
        int len;
        try {
        	OutputStream out = new FileOutputStream(outFile);
        	while((len = is.read(buf))>0) {
				out.write(buf,0,len);
			}
        	out.close();
        	is.close();
		} catch (IOException e) {
			return "Couldn't install file - "+filename+"!";
		}
		return null;
    }
    

    private void checkDirs() {
    	File dir = new File(this.coretask.DATA_FILE_PATH);
    	if (dir.exists() == false) {
    			this.displayToastMessage("Application data-dir does not exist!");
    	}
    	else {
    		String[] dirs = { "/bin", "/var", "/conf", "/library" };
    		for (String dirname : dirs) {
    			dir = new File(this.coretask.DATA_FILE_PATH + dirname);
    	    	if (dir.exists() == false) {
    	    		if (!dir.mkdir()) {
    	    			this.displayToastMessage("Couldn't create " + dirname + " directory!");
    	    		}
    	    	}
    	    	else {
    	    		Log.d(MSG_TAG, "Directory '"+dir.getAbsolutePath()+"' already exists!");
    	    	}
    		}
    	}
    }
    
    // Display Toast-Message
	public void displayToastMessage(String message) {
		Toast.makeText(this, message, Toast.LENGTH_LONG).show();
	}
    
    public int getVersionNumber() {
    	int version = -1;
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionCode;
        } catch (Exception e) {
            Log.e(MSG_TAG, "Package name not found", e);
        }
        return version;
    }
    
    public String getVersionName() {
    	String version = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception e) {
            Log.e(MSG_TAG, "Package name not found", e);
        }
        return version;
    }
    
   	public void clientConnectEnable(boolean enable) {
   		if (enable == true) {
			if (this.clientConnectThread == null || this.clientConnectThread.isAlive() == false) {
				this.clientConnectThread = new Thread(new ClientConnect());
				this.clientConnectThread.start();
			}
   		} else {
	    	if (this.clientConnectThread != null)
	    		this.clientConnectThread.interrupt();
   		}
   	}

    
    class ClientConnect implements Runnable {

        private ArrayList<String> knownLeases = new ArrayList<String>();
        private Hashtable<String, ClientData> currentLeases = new Hashtable<String, ClientData>();
        private long timestampLeasefile = -1;

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
            	Log.d(MSG_TAG, "Checking for new clients ... ");
            	// Checking leasefile
                long currentTimestampLeaseFile = TetherApplication.this.coretask.getModifiedDate(TetherApplication.this.coretask.DATA_FILE_PATH + "/var/dnsmasq.leases");
                if (this.timestampLeasefile != currentTimestampLeaseFile) {
                    try {
                    	// Getting current dns-leases
                        this.currentLeases = TetherApplication.this.coretask.getLeases();
                        
                        // Cleaning-up knownLeases after a disconnect (dhcp-release)
                        for (String lease : this.knownLeases) {
                            if (this.currentLeases.containsKey(lease) == false) {
                            	Log.d(MSG_TAG, "Removing '"+lease+"' from known-leases!");
                                this.knownLeases.remove(lease);
                            }
                        }
                        
                        Enumeration<String> leases = this.currentLeases.keys();
                        while (leases.hasMoreElements()) {
                            String mac = leases.nextElement();
                            Log.d(MSG_TAG, "Mac-Address: '"+mac+"' - Known Lease: "+knownLeases.contains(mac));
                        	// AddClientData to TetherApplication-Class for AccessControlActivity
                            this.sendClientMessage(this.currentLeases.get(mac), CLIENT_CONNECT_AUTHORIZED);
                        }
                        this.timestampLeasefile = currentTimestampLeaseFile;
                    } catch (Exception e) {
                        Log.d(MSG_TAG, "Unexpected error detected - Here is what I know: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void sendClientMessage(ClientData clientData, int connectType) {
            Message m = new Message();
            m.obj = clientData;
            m.what = connectType;
            TetherApplication.this.clientConnectHandler.sendMessage(m);
        }
    }
   	
    public void dnsUpdateEnable(boolean enable) {
    	this.dnsUpdateEnable(null, enable);
    }
 
   	public void dnsUpdateEnable(String[] dns, boolean enable) {
   		if (enable == true) {
			if (this.dnsUpdateThread == null || this.dnsUpdateThread.isAlive() == false) {
				this.dnsUpdateThread = new Thread(new DnsUpdate(dns));
				this.dnsUpdateThread.start();
			}
   		} else {
	    	if (this.dnsUpdateThread != null)
	    		this.dnsUpdateThread.interrupt();
   		}
   	}
       
    class DnsUpdate implements Runnable {

    	String[] dns;
    	
    	public DnsUpdate(String[] dns) {
    		this.dns = dns;
    	}
    	
		@Override
		public void run() {
            while (!Thread.currentThread().isInterrupted()) {
            	String[] currentDns = TetherApplication.this.coretask.getCurrentDns();
            	if (this.dns == null || this.dns[0].equals(currentDns[0]) == false || this.dns[1].equals(currentDns[1]) == false) {
            		this.dns = TetherApplication.this.coretask.updateResolvConf();
            	}
                // Taking a nap
       			try {
    				Thread.sleep(2000);
    			} catch (InterruptedException e) {
    				Thread.currentThread().interrupt();
    			}
            }
		}
    }
   	
   	public void trafficCounterEnable(boolean enable) {
   		if (enable == true) {
			if (this.trafficCounterThread == null || this.trafficCounterThread.isAlive() == false) {
				this.trafficCounterThread = new Thread(new TrafficCounter());
				this.trafficCounterThread.start();
			}
   		} else {
	    	if (this.trafficCounterThread != null)
	    		this.trafficCounterThread.interrupt();
   		}
   	}
   	
   	class TrafficCounter implements Runnable {
   		private static final int INTERVAL = 2;  // Sample rate in seconds.
   		long previousDownload;
   		long previousUpload;
   		long lastTimeChecked;
   		
   		@Override
   		public void run() {
   			this.previousDownload = this.previousUpload = 0;
   			this.lastTimeChecked = new Date().getTime();
   			
   			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				// nothing
			}
   			
	        long [] trafficCountAtStart = TetherApplication.this.coretask.getDataTraffic(
	        		TetherApplication.this.tetherNetworkDevice);
   			
   			while (!Thread.currentThread().isInterrupted()) {
		        // Check data count
		        long [] trafficCount = TetherApplication.this.coretask.getDataTraffic(
		        		TetherApplication.this.tetherNetworkDevice);
		        long currentTime = new Date().getTime();
		        float elapsedTime = (float) ((currentTime - this.lastTimeChecked) / 1000);
		        this.lastTimeChecked = currentTime;
		        DataCount datacount = new DataCount();
		        datacount.totalUpload = trafficCount[0]-trafficCountAtStart[0];
		        datacount.totalDownload = trafficCount[1]-trafficCountAtStart[1];
		        datacount.uploadRate = (long) ((datacount.totalUpload - this.previousUpload)*8/elapsedTime);
		        datacount.downloadRate = (long) ((datacount.totalDownload - this.previousDownload)*8/elapsedTime);
				Message message = Message.obtain();
				message.what = MainActivity.MESSAGE_TRAFFIC_COUNT;
				message.obj = datacount;
				MainActivity.currentInstance.viewUpdateHandler.sendMessage(message); 
				this.previousUpload = datacount.totalUpload;
				this.previousDownload = datacount.totalDownload;
                try {
                    Thread.sleep(INTERVAL * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
   			}
			Message message = Message.obtain();
			message.what = MainActivity.MESSAGE_TRAFFIC_END;
			MainActivity.currentInstance.viewUpdateHandler.sendMessage(message); 
   		}
   	}
   	
   	public class DataCount {
   		// Total data uploaded
   		public long totalUpload;
   		// Total data downloaded
   		public long totalDownload;
   		// Current upload rate
   		public long uploadRate;
   		// Current download rate
   		public long downloadRate;
   	}
}
