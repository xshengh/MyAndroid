
package com.miui.common.external;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BinderManager {

    private static final String TAG = BinderManager.class.getSimpleName();

    private Context mContext;
    private static BinderManager binderManager;
    // binder的缓存，key是Action
    private Map<String, BinderTask> binderCache = Collections
            .synchronizedMap(new HashMap<String, BinderTask>());

    public interface BindServiceCallback {
        public boolean onGetBinder(IBinder service);
    }

    private BinderManager(Context context) {
        mContext = context.getApplicationContext();
    }

    public static void init(Context context) {
        if (binderManager == null) {
            binderManager = new BinderManager(context);
        }
    }

    public synchronized static BinderManager getInstance() {
        if (binderManager == null) {
            throw new RuntimeException("please init BinderManager first");
        }
        return binderManager;
    }

    class BinderTask {
        // intent的action
        String action;
        // intent中setPackage设置的值
        String actionPackage;
        // 是否已经执行绑定，不是是否已经绑定
        boolean isExecBind = false;
        // 绑定的次数
        int bindCount = 0;
        // 绑定的binder，如果绑定完成，则不为空
        IBinder binder;
        // 用于绑定这个binder的conn
        BindServiceConn bindServiceConn;
        // 这个binder的锁
        Object binderLock = new Object();
    }

    public void getService(String action, String packageName, final BindServiceCallback callback) {
        BinderTask binderTask = binderCache.get(action);
        if (binderTask == null) {
            binderTask = new BinderTask();
            binderTask.action = action;
            binderTask.actionPackage = packageName;
            binderTask.bindServiceConn = new BindServiceConn(action);
            binderCache.put(binderTask.action, binderTask);
        }
        binderTask.bindCount++;
        android.util.Log.i(TAG, "action:" + action + "   bindCount : " + binderTask.bindCount);
        if (binderTask.binder != null) {
            invokeCallback(callback, binderTask, binderTask.binder);
        } else {
            synchronized (binderTask.binderLock) {
                // 进到同步代码里，可能已经获取到了，先取一次，获取到就直接返回
                binderTask = binderCache.get(action);
                if (binderTask != null && binderTask.binder != null) {
                    invokeCallback(callback, binderTask, binderTask.binder);
                    return;
                }
                // 依然没获取到，加入callback队列
                binderTask.bindServiceConn.addCallback(callback);
                if (!binderTask.isExecBind) {
                    Intent intent = new Intent(action);
                    intent.setPackage(binderTask.actionPackage);
                    mContext.bindService(intent, binderTask.bindServiceConn,
                            Service.BIND_AUTO_CREATE);
                    binderTask.isExecBind = true;
                }
            }
        }
    }

    public void releaseService(String action) {
        BinderTask binderTask = binderCache.get(action);
        releaseService(binderTask);
    }

    public void releaseService(BinderTask binderTask) {
        if (binderTask != null) {
            synchronized (binderTask.binderLock) {
                binderTask.bindCount--;
                android.util.Log.i(TAG,
                        "action:" + binderTask.action + "   bindCount : " + binderTask.bindCount);
                if (binderTask.bindCount == 0) {
                    mContext.unbindService(binderTask.bindServiceConn);
                    binderCache.remove(binderTask.action);
                }
            }
        }
    }

    private void invokeCallback(BindServiceCallback callback, BinderTask binderTask,
            IBinder service) {
        if (callback != null) {
            if (callback.onGetBinder(service)) {
                releaseService(binderTask);
            }
        }
    }

    private class BindServiceConn implements ServiceConnection {

        private List<BindServiceCallback> callbackList = new ArrayList<BindServiceCallback>();

        private String action;

        BindServiceConn(String action) {
            this.action = action;
        }

        public void addCallback(BindServiceCallback callback) {
            callbackList.add(callback);
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BinderTask binderTask = binderCache.get(action);
            if (binderTask != null) {
                binderTask.binder = service;
                binderTask.isExecBind = true;
            }
            synchronized (binderTask.binderLock) {
                for (BindServiceCallback callback : callbackList) {
                    invokeCallback(callback, binderTask, service);
                }
                callbackList.clear();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            BinderTask binderTask = binderCache.get(action);
            if (binderTask != null) {
                binderTask.binder = null;
                binderTask.isExecBind = false;
            }
        }
    }
}
