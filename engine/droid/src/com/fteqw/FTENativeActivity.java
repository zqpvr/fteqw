package com.fteqw;
import android.view.inputmethod.InputMethodManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.WindowManager;

public class FTENativeActivity extends android.app.Activity implements android.view.SurfaceHolder.Callback2, android.view.ViewTreeObserver.OnGlobalLayoutListener
{
	//Native functions and stuff
	private native boolean startup(String externalDataPath, String libraryPath);
	private native void openfile(String url);
	private native void surfacechange(boolean teardown, boolean restart, android.view.SurfaceHolder holder, android.view.Surface surface);
	private native void shutdown();

	private static native void keypress(int devid, boolean down, int androidkey, int unicode);
	private static native void mousepress(int devid, int buttonbits);
	private static native void motion(int devid, int action, float x, float y, float z, float size);
	private static native boolean wantrelative();
	private static native void axis(int devid, int axisid, float value);
//	private static native void oncreate(String bindir, String basedir, byte[] savedstate);
	static
	{
		//Load the bundled GnuTLS (TLS/DTLS for the online broker) up front. FTE's net_ssl_gnutls
		//backend dlopen()s "libgnutls.so" lazily, but a bare dlopen by name can fail to locate an
		//app-bundled lib; System.loadLibrary resolves it via the app's lib path, after which FTE's
		//dlopen just gets the already-loaded handle. Optional, so don't let its absence be fatal.
		try { System.loadLibrary("gnutls"); } catch (Throwable t) { android.util.Log.w("FTEDroid", "gnutls not loaded: " + t); }
		System.loadLibrary("ftedroid");	//registers the methods properly.
	}

	static class NativeContentView extends android.view.View
	{
		FTENativeActivity mActivity;
		public NativeContentView(android.content.Context context)
		{
			super(context);
		}
		public NativeContentView(android.content.Context context, android.util.AttributeSet attrs)
		{
			super(context, attrs);
		}
	}
	private NativeContentView mNativeContentView;

//SurfaceHolder.Callback2 methods
	public void surfaceRedrawNeeded(android.view.SurfaceHolder holder)
	{	//we constantly redraw.
	}
	public void surfaceCreated(android.view.SurfaceHolder holder)
	{
//		surfacechange(false, true, holder.getSurface());
	}
	public void surfaceChanged(android.view.SurfaceHolder holder, int format, int width, int height)
	{
		surfacechange(true, true, holder, holder.getSurface());
	}
	public void surfaceDestroyed(android.view.SurfaceHolder holder)
	{
		surfacechange(true, false, null, null);
	}

//OnGlobalLayoutListener methods
    public void onGlobalLayout()
	{
/*		mNativeContentView.getLocationInWindow(mLocation);
		int w = mNativeContentView.getWidth();
		int h = mNativeContentView.getHeight();
		if (mLocation[0] != mLastContentX || mLocation[1] != mLastContentY || w != mLastContentWidth || h != mLastContentHeight)
		{
			mLastContentX = mLocation[0];
			mLastContentY = mLocation[1];
			mLastContentWidth = w;
			mLastContentHeight = h;
			if (!mDestroyed) {
				onContentRectChangedNative(mNativeHandle, mLastContentX,
						mLastContentY, mLastContentWidth, mLastContentHeight);
			}
		}
*/	}

//Activity methods
	@Override
	protected void onCreate(android.os.Bundle savedInstanceState)
	{
		getWindow().takeSurface(this);
		getWindow().setFormat(android.graphics.PixelFormat.RGB_565);
		getWindow().setSoftInputMode(
				WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
				| WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		mNativeContentView = new NativeContentView(this);
		mNativeContentView.mActivity = this;
		setContentView(mNativeContentView);
		mNativeContentView.requestFocus();
		mNativeContentView.getViewTreeObserver().addOnGlobalLayoutListener(this);

//		byte[] nativeSavedState = savedInstanceState != null
//			? savedInstanceState.getByteArray(KEY_NATIVE_SAVED_STATE) : null;
		//Bundled game data ships inside the APK (assets/nzp). Extract it to the writable data
		//dir on first run (or when the bundled version changes) BEFORE startup() so the engine
		//finds it. Fast no-op once installed (version-file check). The render surface is only
		//created after onCreate returns, so doing this here keeps startup ordering correct.
		String dataPath = getAbsolutePath(getExternalFilesDir(null));
		try { extractGameData(dataPath); } catch (Exception e) { e.printStackTrace(); }

		startup(dataPath, getNativeLibraryDirectory());
		handleIntent(getIntent());

		//Let the game draw into the display-cutout (camera notch) area in landscape so it
		//genuinely fills the screen, then go immersive-fullscreen (hide status/nav bars).
		if (android.os.Build.VERSION.SDK_INT >= 28)
		{
			WindowManager.LayoutParams lp = getWindow().getAttributes();
			lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
			getWindow().setAttributes(lp);
		}
		setFullscreen();

		super.onCreate(savedInstanceState);

		//Volume keys should drive the media stream (the game mixes through STREAM_MUSIC), so the
		//slider that pops up matches what actually gets louder/quieter.
		setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

		audioStart();
	}

	//Immersive fullscreen: hide the status + navigation bars. STICKY lets a swipe reveal them
	//transiently. setSystemUiVisibility is deprecated but still the most broadly-compatible way.
	private void setFullscreen()
	{
		try
		{
			getWindow().getDecorView().setSystemUiVisibility(
				  0x00000004	//SYSTEM_UI_FLAG_FULLSCREEN
				| 0x00000002	//SYSTEM_UI_FLAG_HIDE_NAVIGATION
				| 0x00001000	//SYSTEM_UI_FLAG_IMMERSIVE_STICKY
				| 0x00000100	//SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				| 0x00000200	//SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				| 0x00000400);	//SYSTEM_UI_FLAG_LAYOUT_STABLE
		}
		catch (Throwable t) {}
	}
	@Override public void onWindowFocusChanged(boolean hasFocus)
	{
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus)	//re-assert immersive mode whenever focus returns (it gets reset by dialogs, etc.)
			setFullscreen();
	}

	//Copies assets/nzp/** out of the APK into <dataPath>/nzp on first run (or when the bundled
	//version differs). The engine reads loose files from the data dir, so this makes a fully
	//self-contained APK. Reads the APK zip directly - fast and handles AGP asset compression.
	private void extractGameData(String dataPath) throws Exception
	{
		if (dataPath == null)
			return;
		final String prefix = "assets/nzp/";
		java.util.zip.ZipFile zip = new java.util.zip.ZipFile(getPackageCodePath());
		try
		{
			//skip extraction if already installed at the same version
			String bundledVer = readZipString(zip, prefix + "version.txt");
			java.io.File verFile = new java.io.File(dataPath, "nzp/version.txt");
			if (bundledVer != null && verFile.exists() && bundledVer.equals(readFileString(verFile)))
				return;
			android.util.Log.i("FTEDroid", "extracting bundled game data...");
			byte[] buf = new byte[65536];
			java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
			while (en.hasMoreElements())
			{
				java.util.zip.ZipEntry ze = en.nextElement();
				String name = ze.getName();
				if (!name.startsWith(prefix))
					continue;
				java.io.File out = new java.io.File(dataPath, name.substring("assets/".length()));
				if (ze.isDirectory()) { out.mkdirs(); continue; }
				if (out.getParentFile() != null) out.getParentFile().mkdirs();
				java.io.InputStream is = zip.getInputStream(ze);
				java.io.FileOutputStream os = new java.io.FileOutputStream(out);
				int n;
				while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
				os.close();
				is.close();
			}
			android.util.Log.i("FTEDroid", "game data extracted.");
		}
		finally { zip.close(); }
	}
	private static String readZipString(java.util.zip.ZipFile zip, String entry) throws Exception
	{
		java.util.zip.ZipEntry ze = zip.getEntry(entry);
		if (ze == null) return null;
		return streamToString(zip.getInputStream(ze));
	}
	private static String readFileString(java.io.File f) throws Exception
	{
		return streamToString(new java.io.FileInputStream(f));
	}
	private static String streamToString(java.io.InputStream is) throws Exception
	{
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		byte[] b = new byte[4096]; int n;
		while ((n = is.read(b)) > 0) bo.write(b, 0, n);
		is.close();
		return bo.toString("UTF-8").trim();
	}

	//audio: FTENativeActivity's frame loop runs in native code, so (unlike FTEDroidActivity)
	//there is no Java-side per-frame trigger to start playback. This thread polls the engine's
	//audio parameters and begins streaming once the sound device is up.
	private audiothreadclass audiothread;
	private class audiothreadclass extends Thread
	{
		volatile boolean timetodie;
		@Override
		public void run()
		{
			byte[] audbuf = new byte[2048];
			android.media.AudioTrack at = null;
			while (!timetodie && at == null)
			{
				int sspeed = FTEDroidEngine.audioinfo(0);
				int schannels = FTEDroidEngine.audioinfo(1);
				int sbits = FTEDroidEngine.audioinfo(2);
				if (sspeed <= 0)
				{
					try { Thread.sleep(100); } catch (InterruptedException e) {}
					continue;
				}
				try
				{
					int chans = (schannels >= 2) ? android.media.AudioFormat.CHANNEL_OUT_STEREO : android.media.AudioFormat.CHANNEL_OUT_MONO;
					int enc = (sbits == 8) ? android.media.AudioFormat.ENCODING_PCM_8BIT : android.media.AudioFormat.ENCODING_PCM_16BIT;
					int minbuf = android.media.AudioTrack.getMinBufferSize(sspeed, chans, enc);
					//Low audio latency: the old 2*getMinBufferSize buffer with no perf hint was ~160ms.
					//On API 26+ request the fast/low-latency path (the engine already outputs 48kHz =
					//device native) with a small buffer of a few native bursts (~20ms) instead.
					if (android.os.Build.VERSION.SDK_INT >= 26)
					{
						int framebytes = (schannels >= 2 ? 2 : 1) * (sbits == 8 ? 1 : 2);
						int sz = Math.max(minbuf, 4096);
						try {
							android.media.AudioManager am = (android.media.AudioManager)getSystemService(android.content.Context.AUDIO_SERVICE);
							int nf = Integer.parseInt(am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
							if (nf > 0) sz = Math.max(nf * framebytes * 4, 4096);	//~4 native bursts, >= one audbuf write
						} catch (Throwable t) {}
						at = new android.media.AudioTrack.Builder()
							.setAudioAttributes(new android.media.AudioAttributes.Builder()
								.setUsage(android.media.AudioAttributes.USAGE_GAME)
								.setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
							.setAudioFormat(new android.media.AudioFormat.Builder()
								.setSampleRate(sspeed).setChannelMask(chans).setEncoding(enc).build())
							.setBufferSizeInBytes(sz)
							.setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
							.setTransferMode(android.media.AudioTrack.MODE_STREAM)
							.build();
					}
					else
						at = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, sspeed, chans, enc, 2 * minbuf, android.media.AudioTrack.MODE_STREAM);
					at.play();
				}
				catch (Throwable e) { return; }
			}
			if (at == null) return;
			while (!timetodie)
			{
				int avail = FTEDroidEngine.paintaudio(audbuf, audbuf.length);
				if (avail > 0)
					at.write(audbuf, 0, avail);
			}
			at.stop();
			at.release();
		}
		public void killoff()
		{
			timetodie = true;
			try { join(); } catch (InterruptedException e) {}
		}
	};
	private void audioStart()
	{
		if (audiothread == null)
		{
			audiothread = new audiothreadclass();
			audiothread.start();
		}
	}
	private void audioStop()
	{
		if (audiothread != null)
		{
			audiothread.killoff();
			audiothread = null;
		}
	}
	@Override protected void onDestroy()
	{
		audioStop();
		super.onDestroy();
	}

	//random helpers
	private void handleIntent(android.content.Intent intent)
	{
		String s = intent.getScheme();
		if (s=="content")
		{
			android.database.Cursor cursor = this.getContentResolver().query(intent.getData(), null, null, null, null);
			cursor.moveToFirst();   
			String myloc = cursor.getString(0);
			cursor.close();
		}
		else
			openfile(intent.getDataString());
	}
	private static String getAbsolutePath(java.io.File file)
	{
		return (file != null) ? file.getAbsolutePath() : null;
	}
	public String getNativeLibraryDirectory()
	{
		android.content.Context context = getApplicationContext();
		int sdk_level = android.os.Build.VERSION.SDK_INT;
		if (sdk_level >= android.os.Build.VERSION_CODES.GINGERBREAD)
		{
			try
			{
				String secondary = (String) android.content.pm.ApplicationInfo.class.getField("nativeLibraryDir").get(context.getApplicationInfo());
				return secondary;
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
			return null;
		}
		if (sdk_level >= android.os.Build.VERSION_CODES.DONUT)
			return context.getApplicationInfo().dataDir + "/lib";
		return "/data/data/" + context.getPackageName() + "/lib";
	}

	//called by C code on errors / quitting.
	public void showMessageAndQuit(String errormessage)
	{
		final android.app.Activity act = this;
		final String errormsg = errormessage;
		if (errormsg.equals(""))
		{	//just quit
			finish();
			System.exit(0);
		}
		else runOnUiThread(new Runnable()
		{	//show an error message, then quit.
			public void run()
			{
//				act.getView().setVisibility(android.view.View.GONE);
				android.app.AlertDialog ad = new android.app.AlertDialog.Builder(act).create();
				ad.setTitle("Fatal Error");
				ad.setMessage(errormsg);
				ad.setCancelable(false);
				ad.setButton("Ok", new android.content.DialogInterface.OnClickListener()
				{
					public void onClick(android.content.DialogInterface dialog, int which)
					{
						finish();
						System.exit(0);
					}
				});
				ad.show();
			}
		});
	}
	public void updateScreenKeepOn(final boolean keepon)
	{
		final android.app.Activity act = this;
		runOnUiThread(new Runnable()
		{
			public void run()
			{
				if (keepon)
					act.getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				else
					act.getWindow().setFlags(0, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			}
		});
	}


	//called by C code to set orientation.
	public void updateOrientation(String orientation)
	{
		final String ors = orientation;
		runOnUiThread(new Runnable()
		{
			public void run()
			{
				int ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR;
				if (ors.equalsIgnoreCase("unspecified"))
					ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
				else if (ors.equalsIgnoreCase("landscape"))
					ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
				else if (ors.equalsIgnoreCase("portrait"))
					ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
				else if (ors.equalsIgnoreCase("user"))
					ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER;
				else if (ors.equalsIgnoreCase("behind"))
					ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
				else if (ors.equalsIgnoreCase("sensor"))
					ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR;
				else if (ors.equalsIgnoreCase("nosensor"))
					ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
				//the following are api level 9+
				else if (ors.equalsIgnoreCase("sensorlandscape"))
					ori = 6;//android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
				else if (ors.equalsIgnoreCase("sensorportrait"))
					ori = 7;//android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
				else if (ors.equalsIgnoreCase("reverselandscape"))
					ori = 8;//android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
				else if (ors.equalsIgnoreCase("reverseportrait"))
					ori = 9;//android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
				else if (ors.equalsIgnoreCase("fullsensor"))
					ori = 10;//android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
				//and the default, because specifying it again is always useless.
				else
					ori = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR;
				android.util.Log.i("FTEDroid", "Orientation changed to " + ori + " (" + ors + ").");
				setRequestedOrientation(ori);
			}
		});
	};


	//keyboard stuff, called from C.
	public void showKeyboard(int softkeyflags)
	{	//needed because the ndk's ANativeActivity_showSoftInput is defective
		final android.app.Activity act = this;
		final int flags = softkeyflags;
		runOnUiThread(new Runnable()
		{
			public void run()
			{
				if (flags != 0)
				{
					InputMethodManager imm = (InputMethodManager)getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
					imm.showSoftInput(act.getWindow().getDecorView(), InputMethodManager.SHOW_FORCED);
				}
				else
				{
					InputMethodManager imm = (InputMethodManager)getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
					imm.hideSoftInputFromWindow(act.getWindow().getDecorView().getWindowToken(), 0);
				}
			}
		});
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event)
	{	//needed because AKeyEvent_getUnicode is missing completely.
		//Let the system handle the volume keys so Android changes the volume AND shows its slider
		//overlay. Otherwise we consume every key below (return true) and volume control is dead.
		switch (event.getKeyCode())
		{
		case KeyEvent.KEYCODE_VOLUME_UP:
		case KeyEvent.KEYCODE_VOLUME_DOWN:
		case KeyEvent.KEYCODE_VOLUME_MUTE:
			return super.dispatchKeyEvent(event);
		}
		int act = event.getAction();
		//Tag gamepad input with a non-zero device id so the game (CSQC) detects a controller
		//(it keys glyph prompts and aim assist off devid>0). in_forceseat routes it to player 1.
		int kdev = ((event.getSource() & (SOURCE_GAMEPAD|SOURCE_JOYSTICK)) != 0) ? 1 : 0;
		if (act == KeyEvent.ACTION_DOWN)
		{
			int metastate = event.getMetaState();
			int unichar = event.getUnicodeChar(metastate);
			if (unichar == 0)
				unichar = event.getUnicodeChar();
			if (unichar == 0)
				unichar = event.getDisplayLabel();

			keypress(kdev, true, event.getKeyCode(), unichar);
			return true;
		}
		else if (act == KeyEvent.ACTION_UP)
		{
			keypress(kdev, false, event.getKeyCode(), 0);
			return true;
		}
		else
			android.util.Log.i("FTEDroid", "other type of event");
		//ignore ACTION_MULTIPLE or whatever it is, apparently its deprecated anyway.

		return super.dispatchKeyEvent(event);
	}

	private static boolean canrelative;
	private static int AXIS_RELATIVE_X;//MotionEvent 24
	private static int AXIS_RELATIVE_Y;//MotionEvent 24
	private static java.lang.reflect.Method MotionEvent_getAxisValueP; //MotionEvent 12
	private static int SOURCE_MOUSE = 0x00002002;	//InputDevice 9
	//private static int SOURCE_STYLUS = 0x00004002;	//InputDevice 14
	//private static int SOURCE_STYLUS = 0x00004002;	//InputDevice 14
	private static int SOURCE_MOUSE_RELATIVE = 0x00020004;	//InputDevice 26
	private static boolean canbuttons;
	private static java.lang.reflect.Method MotionEvent_getButtonState; //MotionEvent 14

	private static boolean canjoystick;
	private static java.lang.reflect.Method MotionEvent_getAxisValueJ;
	private static java.lang.reflect.Method InputDevice_getMotionRange;
	private static int SOURCE_JOYSTICK = 0x01000010; //InputDevice 12
	private static int SOURCE_GAMEPAD = 0x00000401; //InputDevice 12
	private static int AXIS_X;
	private static int AXIS_Y;
	private static int AXIS_LTRIGGER;
	private static int AXIS_Z;
	private static int AXIS_RZ;
	private static int AXIS_RTRIGGER;
	static
	{
		//if (android.os.Build.VERSION.SDK_INT >= 12)
		try
		{
			MotionEvent_getAxisValueP = MotionEvent.class.getMethod("getAxisValue", int.class, int.class); //api12
			java.lang.reflect.Field relX = MotionEvent.class.getField("AXIS_RELATIVE_X");	//api24ish
			java.lang.reflect.Field relY = MotionEvent.class.getField("AXIS_RELATIVE_Y");	//api24ish
			AXIS_RELATIVE_X = (Integer)relX.get(null);
			AXIS_RELATIVE_Y = (Integer)relY.get(null);
//			SOURCE_MOUSE = (Integer)InputDevice.class.getField("SOURCE_MOUSE").get(null);
//			SOURCE_MOUSE_RELATIVE = (Integer)InputDevice.class.getField("SOURCE_MOUSE_RELATIVE").get(null);
			canrelative = true;	//yay, no exceptions.
			android.util.Log.i("FTEDroid", "relative mouse supported");

			MotionEvent_getButtonState = MotionEvent.class.getMethod("getButtonState");		//api14ish
			canbuttons = true;
			android.util.Log.i("FTEDroid", "mouse buttons supported");
		} catch(Exception e) {
			canrelative = false;
			android.util.Log.i("FTEDroid", "relative mouse not supported");
		}
		try
		{
			MotionEvent_getAxisValueJ = MotionEvent.class.getMethod("getAxisValue", int.class); //api12
			InputDevice_getMotionRange = InputDevice.class.getMethod("getMotionRange", int.class, int.class); //api12 - the (axis,source) overload; it's invoked with two args, so registering the 1-arg version made every call throw
			AXIS_X = (Integer)MotionEvent.class.getField("AXIS_X").get(null);
			AXIS_Y = (Integer)MotionEvent.class.getField("AXIS_Y").get(null);
			AXIS_LTRIGGER = (Integer)MotionEvent.class.getField("AXIS_LTRIGGER").get(null);
			AXIS_Z = (Integer)MotionEvent.class.getField("AXIS_Z").get(null);
			AXIS_RZ = (Integer)MotionEvent.class.getField("AXIS_RZ").get(null);
			AXIS_RTRIGGER = (Integer)MotionEvent.class.getField("AXIS_RTRIGGER").get(null);
//			SOURCE_JOYSTICK = (Integer)InputDevice.class.getField("SOURCE_JOYSTICK").get(null);
//			SOURCE_GAMEPAD = (Integer)InputDevice.class.getField("SOURCE_GAMEPAD").get(null);
			canjoystick = true;
			android.util.Log.i("FTEDroid", "gamepad supported");
		} catch(Exception e) {
			canjoystick = false;
			android.util.Log.i("FTEDroid", "gamepad not supported");
		}
	}
	private static void handleJoystickAxis(MotionEvent event, InputDevice dev, int aaxis, int qaxis)
	{
		try
		{
			final InputDevice.MotionRange range = (InputDevice.MotionRange)InputDevice_getMotionRange.invoke(dev, aaxis, event.getSource());
			if (range != null)
			{
				final float flat = range.getFlat();
				float v = (Float)MotionEvent_getAxisValueJ.invoke(event, aaxis);	//getAxisValue(int) takes ONE arg; the stray second arg threw IllegalArgumentException every call, silently killing all controller axes
				if (Math.abs(v) < flat)
					v = 0;	//read as 0 if its within the deadzone.
				axis(1, qaxis, v);	//devid 1 = "a controller" (see dispatchKeyEvent)
			}
		}
		catch(Exception e)
		{
		}
	}
	//Convert the D-pad hat axis to K_GP_DPAD_* key events (down/up edges). keypress() runs the
	//keycode through mapkey(), so KEYCODE_DPAD_* becomes K_GP_DPAD_* just like a real button.
	private int hatX = 0, hatY = 0;
	private void handleHat(MotionEvent event)
	{
		int devid = 1;	//D-pad is controller input (see dispatchKeyEvent)
		float hx, hy;
		try { hx = event.getAxisValue(MotionEvent.AXIS_HAT_X); hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y); }
		catch (Throwable t) { return; }
		int nx = (hx < -0.5f) ? -1 : (hx > 0.5f ? 1 : 0);
		int ny = (hy < -0.5f) ? -1 : (hy > 0.5f ? 1 : 0);
		if (nx != hatX)
		{
			if (hatX < 0) keypress(devid, false, KeyEvent.KEYCODE_DPAD_LEFT, 0);
			else if (hatX > 0) keypress(devid, false, KeyEvent.KEYCODE_DPAD_RIGHT, 0);
			if (nx < 0) keypress(devid, true, KeyEvent.KEYCODE_DPAD_LEFT, 0);
			else if (nx > 0) keypress(devid, true, KeyEvent.KEYCODE_DPAD_RIGHT, 0);
			hatX = nx;
		}
		if (ny != hatY)
		{
			if (hatY < 0) keypress(devid, false, KeyEvent.KEYCODE_DPAD_UP, 0);
			else if (hatY > 0) keypress(devid, false, KeyEvent.KEYCODE_DPAD_DOWN, 0);
			if (ny < 0) keypress(devid, true, KeyEvent.KEYCODE_DPAD_UP, 0);
			else if (ny > 0) keypress(devid, true, KeyEvent.KEYCODE_DPAD_DOWN, 0);
			hatY = ny;
		}
	}
	private boolean motionEvent(MotionEvent event)
	{
		int id;
		float x, y, size;
		final int act = event.getAction();
		final int src = event.getSource();

		//handle gamepad axis
		if ((event.getSource() & (SOURCE_GAMEPAD|SOURCE_JOYSTICK))!=0 && event.getAction() == MotionEvent.ACTION_MOVE)
		{
			InputDevice dev = event.getDevice();
			handleJoystickAxis(event, dev, AXIS_X, 0);
			handleJoystickAxis(event, dev, AXIS_Y, 1);
			handleJoystickAxis(event, dev, AXIS_LTRIGGER, 2);
			
			handleJoystickAxis(event, dev, AXIS_Z, 3);
			handleJoystickAxis(event, dev, AXIS_RZ, 4);
			handleJoystickAxis(event, dev, AXIS_RTRIGGER, 5);

			//gamepad D-pads usually arrive as a hat axis, not key events; NZ:P binds menu
			//navigation and "use/buy" to the D-pad, so synthesize K_GP_DPAD_* key presses.
			handleHat(event);

			return true;
		}

		final int pointerCount = event.getPointerCount();
		int i;
		for (i = 0; i < pointerCount; i++)
		{
			if (canrelative && src == SOURCE_MOUSE && wantrelative())
			{
				try
				{
					x = (Float)MotionEvent_getAxisValueP.invoke(event, AXIS_RELATIVE_X, i);
					y = (Float)MotionEvent_getAxisValueP.invoke(event, AXIS_RELATIVE_Y, i);
					motion(event.getPointerId(i), 1, x, y, 0, event.getSize(i));
				}
				catch(Exception e)
				{
					android.util.Log.i("FTEDroid", "exception using relative mouse");
					canrelative=false;
				}
			}
			else
			{
				motion(event.getPointerId(i), 0, event.getX(i), event.getY(i), 0, event.getSize(i));
			}
		}

		switch(act & event.ACTION_MASK)
		{
		case MotionEvent.ACTION_DOWN:
		case MotionEvent.ACTION_POINTER_DOWN:
			id = ((act&event.ACTION_POINTER_ID_MASK) >> event.ACTION_POINTER_ID_SHIFT);
			x = event.getX(id);
			y = event.getY(id);
			size = event.getSize(id);
			id = event.getPointerId(id);
			if (canbuttons && src == SOURCE_MOUSE)
			{
				try {mousepress(id, (Integer)MotionEvent_getButtonState.invoke(event));}
				catch(Exception e){}
			}
			else
				motion(id, 2, x, y, 0, size);
			break;
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_POINTER_UP:
			id = ((act&event.ACTION_POINTER_ID_MASK) >> event.ACTION_POINTER_ID_SHIFT);
			x = event.getX(id);
			y = event.getY(id);
			size = event.getSize(id);
			id = event.getPointerId(id);
			if (canbuttons && event.getSource() == SOURCE_MOUSE)
			{
				try {mousepress(id, (Integer)MotionEvent_getButtonState.invoke(event));}
				catch(Exception e){}
			}
			else
				motion(id, 3, x, y, 0, size);
			break;
		case MotionEvent.ACTION_MOVE:
			break;
		default:
			return false;
		}
		return true;
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent event)
	{	//works when mouse is pressed...
		return motionEvent(event);
	}
//	@Override
	public boolean dispatchGenericMotionEvent(MotionEvent event)
	{	//works even when mouse is not pressed
		return motionEvent(event);
	}


	//launching stuff
	private static native int unicodeKeyPress(int unicode);
	@Override
	protected void onNewIntent(android.content.Intent intent)
	{
		handleIntent(intent);
		super.onNewIntent(intent);
	}
}

