package jp.gr.java_conf.neko_daisuki.android.nexec.client;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import android.os.Handler;
import android.app.Activity;
import android.util.SparseArray;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

public class NexecClient {

    public static class Settings {

        private static class Pair {

            private String name;
            private String value;

            public Pair(String name, String value) {
                this.name = name;
                this.value = value;
            }
        }

        private static class Link {

            private String dest;
            private String src;

            public Link(String dest, String src) {
                this.dest = dest;
                this.src = src;
            }
        }

        public String host;
        public int port;
        public String[] args;
        private List<Pair> environment;
        public String[] files;
        private List<Link> links;

        public Settings() {
            environment = new ArrayList<Pair>();
            links = new ArrayList<Link>();
        }

        public void addLink(String dest, String src) {
            links.add(new Link(dest, src));
        }

        public void addEnvironment(String name, String value) {
            environment.add(new Pair(name, value));
        }
    }

    public interface OnGetLineListener {

        public void onGetLine(String line);
    }

    public interface OnFinishListener {

        public void onFinish();
    }

    private static class FakeOnFinishListener implements OnFinishListener {

        public void onFinish() {
        }
    }

    private static class FakeOnGetLineListener implements OnGetLineListener {

        public void onGetLine(String line) {
        }
    }

    private static class IncomingHandler extends Handler {

        private interface UnbindProcedure {

            public void unbind();
        }

        private class TrueUnbindProcedure implements UnbindProcedure {

            public void unbind() {
                mNexecClient.mActivity.unbindService(mNexecClient.mConnection);
            }
        }

        private class FakeUnbindProcedure implements UnbindProcedure {

            public void unbind() {
            }
        }

        private abstract class MessageHandler {

            public abstract void handle(Message msg);
        }

        private abstract class OutputHandler extends MessageHandler {

            private List<Byte> mOutput;

            public OutputHandler() {
                mOutput = new LinkedList<Byte>();
            }

            public void handle(Message msg) {
                byte b = (byte)msg.arg1;
                if ((b != '\r') && (b != '\n')) {
                    mOutput.add(Byte.valueOf(b));
                    return;
                }
                int size = mOutput.size();
                byte[] bytes = new byte[size];
                for (int i = 0; i < size; i++) {
                    bytes[i] = mOutput.get(i).byteValue();
                }
                String s;
                try {
                    s = new String(bytes, "UTF-8");
                }
                catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                    return;
                }
                callOnGetLineListener(s + "\n");
                mOutput.clear();
            }

            protected abstract void callOnGetLineListener(String line);
        }

        private class StdoutHandler extends OutputHandler {

            protected void callOnGetLineListener(String line) {
                mNexecClient.mStdoutOnGetLineListener.onGetLine(line);
            }
        }

        private class StderrHandler extends OutputHandler {

            protected void callOnGetLineListener(String line) {
                mNexecClient.mStderrOnGetLineListener.onGetLine(line);
            }
        }

        private class FinishedHandler extends MessageHandler {

            public void handle(Message msg) {
                mUnbindProcedure.unbind();
                mUnbindProcedure = new FakeUnbindProcedure();
                mNexecClient.mTimer.cancel();
                mNexecClient.mOnFinishListener.onFinish();
                mNexecClient.mOnFinishListener = mNexecClient.mFakeOnFinishListener;
            }
        }

        private NexecClient mNexecClient;
        private UnbindProcedure mUnbindProcedure;
        private SparseArray<MessageHandler> mHandlers;

        public IncomingHandler(NexecClient nexecClient) {
            mNexecClient = nexecClient;
            mUnbindProcedure = new TrueUnbindProcedure();
            mHandlers = new SparseArray<MessageHandler>();
            mHandlers.put(MessageWhat.STDOUT, new StdoutHandler());
            mHandlers.put(MessageWhat.STDERR, new StderrHandler());
            mHandlers.put(MessageWhat.FINISHED, new FinishedHandler());
        }

        public void handleMessage(Message msg) {
            mHandlers.get(msg.what).handle(msg);
        }
    }

    private class ProxyTask extends TimerTask {

        public void run() {
            mPollingTask.run();
        }
    }

    private interface PollingTask {

        public void run();
    }

    private class TruePollingTask implements PollingTask {

        public void run() {
            Message msg = Message.obtain(null, MessageWhat.TELL_STATUS);
            msg.replyTo = mMessenger;
            try {
                mService.send(msg);
            }
            catch (RemoteException e) {
                e.printStackTrace();
                // TODO: Show toast?
            }
        }
    }

    private class FakePollingTask implements PollingTask {

        public void run() {
        }
    }

    private class Connection implements ServiceConnection {

        public void onServiceConnected(ComponentName className, IBinder service) {
            mService = new Messenger(service);
            mPollingTask = new TruePollingTask();
        }

        public void onServiceDisconnected(ComponentName className) {
            mService = null;
        }
    }

    private static final String PACKAGE = "jp.gr.java_conf.neko_daisuki.android.nexec.client";

    private Activity mActivity;
    private Connection mConnection;
    private Messenger mService;     // Messenger to the service.
    private Messenger mMessenger;   // Messenger from the service.
    private Timer mTimer;
    private PollingTask mPollingTask;
    private OnGetLineListener mStdoutOnGetLineListener;
    private OnGetLineListener mStderrOnGetLineListener;
    private OnGetLineListener mFakeOnGetLineListener;
    private OnFinishListener mOnFinishListener;
    private OnFinishListener mFakeOnFinishListener;

    public NexecClient(Activity activity) {
        mActivity = activity;
        mConnection = new Connection();
        mMessenger = new Messenger(new IncomingHandler(this));

        mFakeOnGetLineListener = new FakeOnGetLineListener();
        mStdoutOnGetLineListener = mFakeOnGetLineListener;
        mStderrOnGetLineListener = mFakeOnGetLineListener;
        mFakeOnFinishListener = new FakeOnFinishListener();
        mOnFinishListener = mFakeOnFinishListener;
    }

    public void setOnFinishListener(OnFinishListener l) {
        mOnFinishListener = l != null ? l : mFakeOnFinishListener;
    }

    public void setStdoutOnGetLineListener(OnGetLineListener l) {
        mStdoutOnGetLineListener = l != null ? l : mFakeOnGetLineListener;
    }

    public void setStderrOnGetLineListener(OnGetLineListener l) {
        mStderrOnGetLineListener = l != null ? l : mFakeOnGetLineListener;
    }

    public void request(Settings settings, int requestCode) {
        Intent intent = new Intent();
        intent.setClassName(PACKAGE, getClassName("MainActivity"));
        intent.putExtra("HOST", settings.host);
        intent.putExtra("PORT", settings.port);
        intent.putExtra("ARGS", settings.args);
        intent.putExtra("ENV", encodeEnvironment(settings.environment));
        intent.putExtra("FILES", settings.files);
        intent.putExtra("LINKS", encodeLinks(settings.links));
        mActivity.startActivityForResult(intent, requestCode);
    }

    public void execute(Intent data) {
        Intent intent = new Intent();
        intent.setClassName(PACKAGE, getClassName("MainService"));
        copySessionId(intent, data);
        mActivity.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        mPollingTask = new FakePollingTask();
        startTimer();
    }

    private void startTimer() {
        mTimer = new Timer();
        mTimer.schedule(new ProxyTask(), 0, 10);
    }

    private String getClassName(String name) {
        return String.format("%s.%s", PACKAGE, name);
    }

    private void copySessionId(Intent dest, Intent src) {
        String key = "SESSION_ID";
        dest.putExtra(key, src.getStringExtra(key));
    }

    private String[] encodeEnvironment(List<Settings.Pair> environment) {
        List<String> l = new LinkedList<String>();
        for (Settings.Pair pair: environment) {
            l.add(encodePair(pair.name, pair.value));
        }
        return l.toArray(new String[0]);
    }

    private String[] encodeLinks(List<Settings.Link> links) {
        List<String> l = new LinkedList<String>();
        for (Settings.Link link: links) {
            l.add(encodePair(link.dest, link.src));
        }
        return l.toArray(new String[0]);
    }

    private String encodePair(String name, String value) {
        return String.format("%s:%s", escape(name), escape(value));
    }

    private String escape(String s) {
        StringBuilder buffer = new StringBuilder();
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            buffer.append((c != ':') && (c != '\\') ? "" : "\\").append(c);
        }
        return buffer.toString();
    }
}

/**
 * vim: tabstop=4 shiftwidth=4 expandtab softtabstop=4
 */
