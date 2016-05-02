package com.fan.gazeshutter.service;

import android.graphics.PixelFormat;
import android.os.AsyncTask;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.fan.gazeshutter.MainApplication;
import com.fan.gazeshutter.R;
import com.fan.gazeshutter.event.GazeEvent;
import com.fan.gazeshutter.utils.Const;
import com.fan.gazeshutter.utils.NetworkUtils;

import org.greenrobot.eventbus.EventBus;
import org.zeromq.ZMQ;

/**
 * Created by fan on 3/26/16.
 * ref. https://www.novoda.com/blog/minimal-zeromq-client-server/
 */


public class ZMQReceiveTask extends AsyncTask<String, Float, String> {
    static final int HALO_BTN_NUM = 4;
    static final String TAG = "ZMQReceiveTask";
    static final String SERVER_IP = "192.168.0.117";
    static final String SERVER_PORT = NetworkUtils.PORT;

    static final String SUB_DT    = "dt";
    static final String SUB_GAZE  = "gaze_positions";
    static final String SUB_PUPIL = "pupil_positions";
    static final String SUB_GAZE_ON_SURFACE = "realtime gaze on unnamed"; //[TODO] tend to be changed

    int mFPS;
    View mInfoView, mGazePointView;
    View mHaloBtnView[];
    WindowManager.LayoutParams mInfoTextParams, mGazePointParams;
    WindowManager.LayoutParams mHaloBtnParams[];
    OverlayService mService;

    int mHaloBtnLayout[] = {
        R.layout.service_halo_btn_l,
        R.layout.service_halo_btn_u,
        R.layout.service_halo_btn_r,
        R.layout.service_halo_btn_d};

    public ZMQReceiveTask(OverlayService service){
        mService = service;

        mGazePointView = mService.mLayoutInflater.inflate(R.layout.service_gaze_point, null);
        mGazePointParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mGazePointParams.gravity = Gravity.LEFT | Gravity.TOP;

        //Text Info
        mInfoTextParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mInfoTextParams.gravity = Gravity.RIGHT | Gravity.TOP;
        mInfoView = mService.mLayoutInflater.inflate(R.layout.overlay, null);
        mService.mWindowManager.addView(mInfoView, mInfoTextParams);


        //Halo Btn
        mHaloBtnParams = new  WindowManager.LayoutParams[HALO_BTN_NUM];
        for (Direction dir : Direction.values()) {
            mHaloBtnParams[dir.ordinal()] = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            mHaloBtnParams[dir.ordinal()].gravity = Gravity.LEFT | Gravity.TOP;
        }

        mHaloBtnView = new View[HALO_BTN_NUM];
        for (Direction dir : Direction.values()) {
            mHaloBtnView[dir.ordinal()] = mService.mLayoutInflater.inflate(mHaloBtnLayout[dir.ordinal()], null);
            mService.mWindowManager.addView(mHaloBtnView[dir.ordinal()], mHaloBtnParams[dir.ordinal()]);
            //mHaloBtnView[dir.ordinal()].setVisibility(View.INVISIBLE);
        }


    }

    @Override
    protected String doInBackground(String... params) {

        ZMQ.Context context = ZMQ.context(1);
        ZMQ.Socket socket = context.socket(ZMQ.SUB);

        socket.connect("tcp://"+params[0]+":"+SERVER_PORT);
        socket.subscribe("".getBytes(ZMQ.CHARSET));

        long curTime;
        long prevTime = System.currentTimeMillis();
        //main update loop
        while (!isCancelled()) {
            String address  = socket.recvStr ();
            String contents = socket.recvStr();
            Log.d(TAG,address + " : " + contents);

            Float[] xy = parseMessageToRatio(contents);
            EventBus.getDefault().post(new GazeEvent(xy[0], xy[1]));

            curTime = System.currentTimeMillis();
            if(curTime != prevTime)
                mFPS = (int)(1000/(curTime-prevTime));
            prevTime = curTime;

            publishProgress(xy);
        }

        String result = new String(socket.recv(0));
        socket.close();
        context.term();

        return result;
    }

    @Override
    protected void onProgressUpdate(Float... xy){
        Log.d(TAG, "onProgressUpdate:"+xy[0]+" "+xy[1]);

        //Info
        TextView mInfoTextView = (TextView)mInfoView.findViewById(R.id.txtInfo);
        String x = String.format("%.2f", xy[0]);
        String y = String.format("%.2f", xy[1]);
        mInfoTextView.setText("("+x+", "+y+")\nfps: "+mFPS);

        if(0<=xy[0] && xy[0]<=1 && 0<=xy[1] && xy[1]<=1) {
            //GazePoint
            if(!mGazePointView.isShown()) {
                mService.mWindowManager.addView(mGazePointView, mGazePointParams);
            }
            MainApplication mainApplication = MainApplication.getInstance();
            mGazePointParams.x = (int)(xy[0]*mainApplication.mScreenWidth);
            mGazePointParams.y = (int)((1-xy[1])*mainApplication.mScreenHeight);
            mService.mWindowManager.updateViewLayout(mGazePointView, mGazePointParams);

            //haloBtn
            showHaloBtn(mGazePointParams.x, mGazePointParams.y);
        }
        else {
            if (mGazePointView.isShown()) {
                mService.mWindowManager.removeViewImmediate(mGazePointView);
            }
            for (Direction dir : Direction.values()) {
                mHaloBtnView[dir.ordinal()].setVisibility(View.INVISIBLE);
            }

        }
    }



    @Override
    protected void onPostExecute(String result) {
        Log.d(TAG,"result:"+result);
    }

    @Override
    protected void onCancelled() {
        super.onCancelled();
        Log.d(TAG,"onCanceled");
        mService.mWindowManager.removeView(mGazePointView);
    }

    protected Float[] parseMessageToRatio(String content) {
        content = content.substring(1, content.length() - 1);
        Log.d(TAG, content);

        String[] xy = content.split(",");//[TODO] split w/ regex
        Float x = Float.valueOf(xy[0]);
        Float y = Float.valueOf(xy[1]);
        //Log.d(TAG,"x:"+x+"  y:"+y);

        return new Float[]{x, y};
    }

    protected void showHaloBtn(int x, int y){
        for (Direction dir : Direction.values()) {
            mHaloBtnView[dir.ordinal()].setVisibility(View.VISIBLE);
            mHaloBtnParams[dir.ordinal()].x = dir.getHaloX(x);
            mHaloBtnParams[dir.ordinal()].y = dir.getHaloY(y);
            //Log.d(TAG,"updating"+dir);
            //Log.d(TAG,mHaloBtnParams[dir.ordinal()].x+"  "+mHaloBtnParams[dir.ordinal()].y);
            mService.mWindowManager.updateViewLayout(mHaloBtnView[dir.ordinal()], mHaloBtnParams[dir.ordinal()]);
        }
    }
}

enum Direction {
    LEFT(Const.MIN, Const.DONT_CARE),
    UP(Const.DONT_CARE, Const.MIN),
    RIGHT(Const.MAX, Const.DONT_CARE),
    DOWN(Const.DONT_CARE, Const.MAX);

    private int OFFSET = 38;
    private float xRatio,yRatio;
    static MainApplication mainApplication = MainApplication.getInstance();
    static int xMax = mainApplication.mScreenWidth;
    static int yMax = mainApplication.mScreenHeight;

    Direction(float xRatio, float yRatio) {
        this.xRatio = xRatio;
        this.yRatio = yRatio;
    }

    public int getHaloX(int x){
        if(xRatio==Const.DONT_CARE)
            return x;
        else if(xRatio==Const.MIN)
            return -OFFSET;
        else if(xRatio==Const.MAX)
            return  xMax+OFFSET;
        else
            return 0;
    }

    public int getHaloY(int y){
        if(yRatio==Const.DONT_CARE)
            return y;
        else if(yRatio==Const.MIN)
            return -OFFSET;
        else if(yRatio==Const.MAX)
            return  yMax+OFFSET;
        else
            return 0;
    }
}
