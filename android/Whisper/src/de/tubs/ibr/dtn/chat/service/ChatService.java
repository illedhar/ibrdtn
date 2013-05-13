/*
 * ChatService.java
 * 
 * Copyright (C) 2011 IBR, TU Braunschweig
 *
 * Written-by: Johannes Morgenroth <morgenroth@ibr.cs.tu-bs.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package de.tubs.ibr.dtn.chat.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.StringTokenizer;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import de.tubs.ibr.dtn.api.Block;
import de.tubs.ibr.dtn.api.Bundle;
import de.tubs.ibr.dtn.api.Bundle.ProcFlags;
import de.tubs.ibr.dtn.api.BundleID;
import de.tubs.ibr.dtn.api.DTNClient;
import de.tubs.ibr.dtn.api.DTNClient.Session;
import de.tubs.ibr.dtn.api.DataHandler;
import de.tubs.ibr.dtn.api.GroupEndpoint;
import de.tubs.ibr.dtn.api.Registration;
import de.tubs.ibr.dtn.api.ServiceNotAvailableException;
import de.tubs.ibr.dtn.api.SessionDestroyedException;
import de.tubs.ibr.dtn.api.SingletonEndpoint;
import de.tubs.ibr.dtn.api.TransferMode;
import de.tubs.ibr.dtn.chat.MainActivity;
import de.tubs.ibr.dtn.chat.R;
import de.tubs.ibr.dtn.chat.core.Buddy;
import de.tubs.ibr.dtn.chat.core.Message;
import de.tubs.ibr.dtn.chat.core.Roster;

public class ChatService extends IntentService {
	
	public enum Debug {
		NOTIFICATION
	}
	
	private static final String TAG = "ChatService";
	
	// mark a specific bundle as delivered
	public static final String  MARK_DELIVERED_INTENT = "de.tubs.ibr.dtn.chat.MARK_DELIVERED";
	public static final String  REPORT_DELIVERED_INTENT = "de.tubs.ibr.dtn.chat.REPORT_DELIVERED";
	
	private static final int MESSAGE_NOTIFICATION = 1;
	public static final String ACTION_OPENCHAT = "de.tubs.ibr.dtn.chat.OPENCHAT";
	public static final GroupEndpoint PRESENCE_GROUP_EID = new GroupEndpoint("dtn://chat.dtn/presence");
	private Registration _registration = null;
	private ServiceError _service_error = ServiceError.NO_ERROR;
	private Boolean _screen_off = false;
	
	private static String visibleBuddy = null;
	
    // This is the object that receives interactions from clients.  See
    // RemoteService for a more complete example.
    private final IBinder mBinder = new LocalBinder();
    
    // local roster with the connection to the database
    private Roster roster = null;
    
	// DTN client to talk with the DTN service
	private DTNClient _client = null;
	
	public ChatService() {
		super(TAG);
	}
	
    private DataHandler _data_handler = new DataHandler()
    {
        ByteArrayOutputStream stream = null;
    	Bundle current;

		public void startBundle(Bundle bundle) {
			this.current = bundle;
		}

		public void endBundle() {
			de.tubs.ibr.dtn.api.BundleID received = new de.tubs.ibr.dtn.api.BundleID(this.current);
			
			// run the queue and delivered process asynchronously
			Intent i = new Intent(ChatService.this, ChatService.class);
			i.setAction(MARK_DELIVERED_INTENT);
			i.putExtra("bundleid", received);
			startService(i);

			this.current = null;
		}

		public TransferMode startBlock(Block block) {
			// ignore messages with a size larger than 8k
			if ((block.length > 8196) || (block.type != 1)) return TransferMode.NULL;
			
			// create a new bytearray output stream
			stream = new ByteArrayOutputStream();
			
			return TransferMode.SIMPLE;
		}

		public void endBlock() {
		    if (stream != null) {
                String msg = new String(stream.toByteArray());
                stream = null;
                
                if (current.getDestination().equals(PRESENCE_GROUP_EID))
                {
                    eventNewPresence(current.getSource(), current.getTimestamp().getDate(), msg);
                }
                else
                {
                    eventNewMessage(current.getSource(), current.getTimestamp().getDate(), msg);
                }
		    }
		}

		public void payload(byte[] data) {
		    if (stream == null) return;
		    // write data to the stream
		    try {
                stream.write(data);
            } catch (IOException e) {
                Log.e(TAG, "error on writing payload", e);
            }
		}

		public ParcelFileDescriptor fd() {
			return null;
		}

		public void progress(long current, long length) {
		}

		public void finished(int startId) {
		}
    
		private void eventNewPresence(SingletonEndpoint source, Date created, String payload)
		{
			Log.i(TAG, "Presence received from " + source);
			
			// buddy info
			String nickname = null;
			String presence = null;
			String status = null;
			
			StringTokenizer tokenizer = new StringTokenizer(payload, "\n");
			while (tokenizer.hasMoreTokens())
			{
				String data = tokenizer.nextToken();
				
				// search for the delimiter
				int delimiter = data.indexOf(':');
				
				// if the is no delimiter, ignore the line
				if (delimiter == -1) return;
				
				// split the keyword and data pair
				String keyword = data.substring(0, delimiter);
				String value = data.substring(delimiter + 1, data.length()).trim();
				
				if (keyword.equalsIgnoreCase("Presence"))
				{
					presence = value;
				}
				else if (keyword.equalsIgnoreCase("Nickname"))
				{
					nickname = value;
				}
				else if (keyword.equalsIgnoreCase("Status"))
				{
					status = value;
				}
			}
			
			if (nickname != null)
			{
				eventBuddyInfo(created, source.toString(), presence, nickname, status);
			}
		}
		
		private void eventNewMessage(SingletonEndpoint source, Date created, String payload)
		{
			if (source == null)
			{
				Log.e(TAG, "message source is null!");
			}
			
			Buddy b = getRoster().getBuddy(source.toString());
			Message msg = new Message(null, true, created, new Date(), payload);
			msg.setBuddy(b);
			
			getRoster().storeMessage(msg);
			
			// create a notification
			createNotification(b, msg);
			
			// create a status bar notification
			Log.i(TAG, "New message received!");
		}
    };
    
    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class LocalBinder extends Binder {
    	public ChatService getService() {
            return ChatService.this;
        }
    }

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public void onCreate()
	{
		// lower the thread priority
		android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);

		// call onCreate of the super-class
		super.onCreate();
		
		IntentFilter screen_filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
		screen_filter.addAction(Intent.ACTION_SCREEN_OFF);
		registerReceiver(_screen_receiver, screen_filter);
		_screen_off = false;
		
		// create a new client object
		_client = new DTNClient();

		// create a roster object
		this.roster = new Roster();
		this.roster.open(this);
		
		// create registration
    	_registration = new Registration("chat");
    	_registration.add(PRESENCE_GROUP_EID);
		
		// register own data handler for incoming bundles
		_client.setDataHandler(_data_handler);
		
		try {
			_client.initialize(this, _registration);
			_service_error = ServiceError.NO_ERROR;
		} catch (ServiceNotAvailableException e) {
			_service_error = ServiceError.SERVICE_NOT_FOUND;
		} catch (SecurityException ex) {
			_service_error = ServiceError.PERMISSION_NOT_GRANTED;
		}
		
		Log.i(TAG, "service created.");
	}
	
	private BroadcastReceiver _screen_receiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
				_screen_off = true;
			} else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
				_screen_off = false;
				
				// clear notification
				String visible = ChatService.getVisible();
				if (visible != null) {
					NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
					mNotificationManager.cancel(visible, MESSAGE_NOTIFICATION);
				}
			}
		}
	};
	
	public ServiceError getServiceError() {
		return _service_error;
	}
	
	@Override
	public void onDestroy()
	{
		unregisterReceiver(_screen_receiver);
		
		// close the roster (plus db connection)
		this.roster.close();
		
		// destroy DTN client
		_client.terminate();
		
		// clear all variables
		this.roster = null;
		_client = null;

		super.onDestroy();
		
		Log.i(TAG, "service destroyed.");
	}
	
	public synchronized Roster getRoster()
	{
		return this.roster;
	}
	
	public void sendMessage(Message msg) throws Exception
	{
		Session s = _client.getSession();
		Bundle b = new Bundle();
		
		b.setDestination(new SingletonEndpoint(msg.getBuddy().getEndpoint()));
		
		String lifetime = PreferenceManager.getDefaultSharedPreferences(this).getString("messageduration", "259200");
		b.setLifetime(Long.parseLong(lifetime));
		
		// set status report requests
		b.set(ProcFlags.REQUEST_REPORT_OF_BUNDLE_DELIVERY, true);
		b.setReportto(SingletonEndpoint.ME);
		
		synchronized(this.roster) {
			// send out the message
			BundleID ret = s.send(b, msg.getPayload().getBytes());
			
			if (ret == null)
			{
				throw new Exception("could not send the message");
			}
			else
			{
			    Log.d(TAG, "Bundle sent, BundleID: " + ret.toString());
			}
			
			// set sent id of the message
			msg.setSentId(ret.toString());
			
			// update message into the database
			this.getRoster().reportSent(msg);
		}
	}
	
	public void sendPresence(String presence, String nickname, String status) throws Exception
	{
		Session s = _client.getSession();
		
		String presence_message = "Presence: " + presence + "\n" +
				"Nickname: " + nickname + "\n" +
				"Status: " + status;
		
		BundleID ret = s.send(ChatService.PRESENCE_GROUP_EID, 3600, presence_message.getBytes());
		
		if (ret == null)
		{
			throw new Exception("could not send the message");
		}
		else
		{
		    Log.d(TAG, "Presence sent, BundleID: " + ret.toString());
		}
	}
	
	public void eventBuddyInfo(Date timestamp, String source, String presence, String nickname, String status)
	{
		// get the buddy object
		Buddy b = getRoster().getBuddy(source);
		
		// discard the buddy info, if it is too old
		if (b.getLastSeen() != null)
		{
			if (timestamp.before( b.getLastSeen() ))
			{
				Log.d(TAG, "eventBuddyInfo discarded: " + source + "; " + presence + "; " + nickname + "; " + status);
				return;
			}
		}
		
		Log.d(TAG, "eventBuddyInfo: " + source + "; " + presence + "; " + nickname + "; " + status);
		
		b.setNickname(nickname);
		b.setLastSeen(timestamp);
		b.setStatus(status);
		b.setPresence(presence);
		getRoster().store(b);
	}
	
	public synchronized static void setVisible(String buddyId) {
		if (visibleBuddy != buddyId) visibleBuddy = buddyId;
	}
	
	public synchronized static void setInvisible(String buddyId) {
		if (visibleBuddy == buddyId) visibleBuddy = null;
	}
	
	private synchronized static String getVisible() {
		return visibleBuddy;
	}
	
	public static Boolean isVisible(String buddyId)
	{
		if (visibleBuddy == null) return false;
		return (visibleBuddy.equals(buddyId));
	}
	
	@SuppressWarnings("deprecation")
	private void createNotification(Buddy b, Message msg)
	{
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		
		if (ChatService.isVisible(b.getEndpoint()))
		{
			if (!_screen_off) return;
		}
		
		int icon = R.drawable.ic_message;
		CharSequence tickerText = getString(R.string.new_message_from) + " " + b.getNickname();

		NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
		
		builder.setAutoCancel(true);
		
		CharSequence contentTitle = getString(R.string.new_message);
		CharSequence contentText = b.getNickname() + ":\n" + msg.getPayload();
		
		//int defaults = Notification.DEFAULT_LIGHTS;
		int defaults = 0;
		
		if (prefs.getBoolean("vibrateOnMessage", true)) {
			defaults |= Notification.DEFAULT_VIBRATE;
		}
		
		Intent notificationIntent = new Intent(this, MainActivity.class);
		//notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
		//notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		notificationIntent.setAction(ACTION_OPENCHAT);
		notificationIntent.addCategory("android.intent.category.DEFAULT");
		notificationIntent.putExtra("buddy", b.getEndpoint());
		
		int requestID = (int) System.currentTimeMillis();
		PendingIntent contentIntent = PendingIntent.getActivity(this, requestID, notificationIntent, PendingIntent.FLAG_ONE_SHOT);
		
		builder.setContentTitle(contentTitle);
		builder.setContentText(contentText);
		builder.setSmallIcon(icon);
		builder.setTicker(tickerText);
		builder.setDefaults(defaults);
		builder.setWhen( System.currentTimeMillis() );
		builder.setContentIntent(contentIntent);
		builder.setLights(0xffff0000, 300, 1000);
		builder.setSound( Uri.parse( prefs.getString("ringtoneOnMessage", "content://settings/system/notification_sound") ) );
		
		Notification notification = builder.getNotification();
		
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.notify(b.getEndpoint(), MESSAGE_NOTIFICATION, notification);
		
		if (prefs.getBoolean("ttsWhenOnHeadset", false)) {
			AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
			
			if (am.isBluetoothA2dpOn() || am.isWiredHeadsetOn()) {
				// speak the notification
				Intent tts_intent = new Intent(this, TTSService.class);
				tts_intent.setAction(TTSService.INTENT_SPEAK);
				tts_intent.putExtra("speechText", tickerText + ": " + msg.getPayload());
				startService(tts_intent);
			}
		}
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		String action = intent.getAction();
		
        // create a task to process concurrently
        if (AlarmReceiver.ACTION.equals(action))
        {
			SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(ChatService.this);
			
			// check if the screen is active
			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		    Boolean screenOn = pm.isScreenOn();
			
		    String presence_tag = preferences.getString("presencetag", "auto");
		    String presence_nick = preferences.getString("editNickname", "Nobody");
		    String presence_text = preferences.getString("statustext", "");
		    
		    if (presence_tag.equals("auto"))
		    {
			    if (screenOn)
			    {
			    	presence_tag = "chat";
			    }
			    else
			    {
			    	presence_tag = "away";
			    }
		    }
			
			Log.i(TAG, "push out presence; " + presence_tag);
			try {
				sendPresence(presence_tag, presence_nick, presence_text);
			} catch (Exception e) { }
			
			Editor edit = preferences.edit();
			edit.putLong("lastpresenceupdate", (new Date().getTime()));
			edit.commit();
        }
        // create a task to check for messages
        else if (de.tubs.ibr.dtn.Intent.RECEIVE.equals(action))
        {
        	try {
				while (_client.getSession().queryNext());
			} catch (SessionDestroyedException e) {
				Log.e(TAG, "Can not query for bundle", e);
			} catch (InterruptedException e) {
				Log.e(TAG, "Can not query for bundle", e);
			}
        }
        else if (MARK_DELIVERED_INTENT.equals(action))
        {
        	BundleID bundleid = intent.getParcelableExtra("bundleid");
        	if (bundleid == null) {
        		Log.e(TAG, "Intent to mark a bundle as delivered, but no bundle ID given");
        		return;
        	}
        	
    		try {
    			_client.getSession().delivered(bundleid);
    		} catch (Exception e) {
    			Log.e(TAG, "Can not mark bundle as delivered.", e);
    		}	
        }
        else if (REPORT_DELIVERED_INTENT.equals(action))
        {
			SingletonEndpoint source = intent.getParcelableExtra("source");
			BundleID bundleid = intent.getParcelableExtra("bundleid");
			
        	if (bundleid == null) {
        		Log.e(TAG, "Intent to mark a bundle as delivered, but no bundle ID given");
        		return;
        	}
        	
        	// report delivery to the roster
        	getRoster().reportDelivery(source, bundleid);
        }
	}
	
	public void startDebug(Debug d) {
		switch (d) {
		case NOTIFICATION:
			if (this.getRoster().size() > 0) {
				createNotification(this.getRoster().getFirst(), new Message(null, true, new Date(), new Date(), "Debug message"));
			}
			break;
		}
	}
}
