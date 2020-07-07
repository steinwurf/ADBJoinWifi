package com.steinwurf.adbjoinwifi;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.ProxyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.List;

public class MainActivity extends AppCompatActivity implements CheckSSIDBroadcastReceiver.SSIDFoundListener
{
    private static final String TAG = "adbjoinwifi";

    private static final String WEP_PASSWORD = "WEP";
    private static final String WPA_PASSWORD = "WPA";
    private static final String PEAP_PASSWORD = "PEAP";

    private static final String SSID = "ssid";
    private static final String PASSWORD_TYPE = "password_type";
    private static final String PASSWORD = "password";

    private static final String USERNAME = "username";

    private static final String PROXY_HOST = "proxy_host";
    private static final String PROXY_PORT = "proxy_port";
    private static final String PROXY_BYPASS = "proxy_bypass";
    private static final String PROXY_PAC_URI = "proxy_pac_uri";

    private static final String IP = "ip";
    private static final String GATEWAY = "gateway";
    private static final String PREFIX = "prefix";
    private static final String DNS1 = "dns1";
    private static final String DNS2 = "dns2";

    private static final String CLEAR_DEVICE_ADMIN = "clear_device_admin";
    private static final String CONNECT = "connect";
    private static final String REMOVE = "remove";

    String mSSID;
    String mUsername;
    String mPassword;
    String mPasswordType;
    ProxyInfo mProxyInfo;

    String mIP;
    String mGateway;
    int mPrefix;
    String mDns1;
    String mDns2;

    CheckSSIDBroadcastReceiver broadcastReceiver;
    WifiManager mWifiManager;

    Thread mThread;

    private void printUsage()
    {
        Log.d(TAG, "No datastring provided. use the following adb command:");
        Log.d(TAG,
                "adb shell am start" +
                " -n com.steinwurf.adbjoinwifi/.MainActivity " +
                "-esn [connect|remove|clear_device_admin] " +
                "-e ssid SSID " +
                "-e password_type [WEP|WPA|PEAP] " +
                "[-e username USERNAME] " +
                "-e password PASSWORD " +
                "\nOptional proxy args:\n" +
                "    -e proxy_host HOSTNAME " +
                "-e proxy_port PORT " +
                "[-e proxy_bypass COMMA,SEPARATED,LIST]\n" +
                "    OR\n" +
                "    -e proxy_pac_uri http://my.proxy.config/url" +
                "\nOptional static adddress args:\n" +
                "    -e ip IP " +
                "-e gateway GATEWAY " +
                "-ei prefix PREFIX " +
                "-e dns1 DNS1 " +
                "-e dns2 DNS2");
        Toast.makeText(this, "This application is meant to be used with ADB",
                Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        if (getIntent().hasExtra(CLEAR_DEVICE_ADMIN)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clearDeviceOwner();
            }
            else
            {
                throw new UnsupportedOperationException("API level 21 or higher required for this");
            }
            finish();
            return;
        }

        // Get Content
        mSSID = getIntent().getStringExtra(SSID);
        mPasswordType = getIntent().getStringExtra(PASSWORD_TYPE);
        mPassword = getIntent().getStringExtra(PASSWORD);
        mUsername = getIntent().getStringExtra(USERNAME);

        String proxyHost = getIntent().getStringExtra(PROXY_HOST);
        String proxyPort = getIntent().getStringExtra(PROXY_PORT);
        String proxyBypass = getIntent().getStringExtra(PROXY_BYPASS);
        String proxyPacUri = getIntent().getStringExtra(PROXY_PAC_URI);

        mIP = getIntent().getStringExtra(IP);
        mGateway = getIntent().getStringExtra(GATEWAY);
        mPrefix = getIntent().getIntExtra(PREFIX, 24);
        mDns1 = getIntent().getStringExtra(DNS1);
        mDns2 = getIntent().getStringExtra(DNS2);

        // Setup layout
        LinearLayout layout = new LinearLayout(this);
        setContentView(layout);

        layout.setGravity(Gravity.CENTER);
        layout.setOrientation(LinearLayout.VERTICAL);

        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);

        TextView textview = new TextView(this);
        textview.setText(getString(R.string.waiting));
        textview.setTextSize(20);
        layout.addView(textview, params);

        TextView SSIDtextview = new TextView(this);
        SSIDtextview.setText(mSSID);
        layout.addView(SSIDtextview, params);

        // Check if wifi is enabled, and act accordingly
        mWifiManager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);

        if (!mWifiManager.isWifiEnabled())
            mWifiManager.setWifiEnabled(true);

        // Remove wifi config
        if (getIntent().hasExtra(REMOVE)) {
            if (mSSID != null){
                WifiConfiguration wfc = getExistingWifiConfiguration();
                if (wfc != null) {
                    Log.d(TAG, "Trying to remove SSID: " + mSSID);
                    textview.setText(R.string.trying_to_remove);
                    mWifiManager.removeNetwork(wfc.networkId);
                }
                else {
                    Log.d(TAG, "Not find this SSID: " + mSSID);
                }
            }
            else {
                Log.d(TAG, "Trying to remove all wifi config");
                SSIDtextview.setText(R.string.all);
                removeAllWifiConfig();
            }
            finish();
            return;
        }

        // Connect to wifi
        if (getIntent().hasExtra(CONNECT)) {

            // Validate
            if ((mSSID == null) || // SSID REQUIRED
                    (mPasswordType != null && mPassword == null) || // PASSWORD REQUIRED IF PASSWORD TYPE GIVEN
                    (mPassword != null && mPasswordType == null) || // PASSWORD TYPE REQUIRED IF PASSWORD GIVEN
                    (mPasswordType != null && !mPasswordType.equals(WPA_PASSWORD) && !mPasswordType.equals(WEP_PASSWORD)
                            && !mPasswordType.equals(PEAP_PASSWORD))) // PASSWORD TYPE MUST BE NULL OR WPA OR WEP
            {
                printUsage();
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    mProxyInfo = Proxy.parseProxyInfo(proxyHost, proxyPort, proxyBypass, proxyPacUri);
                } catch (ParseException e) {
                    Log.d(TAG, "Error parsing proxy settings");
                    printUsage();
                    return;
                }
            }

            Log.d(TAG, "Trying to join:");
            Log.d(TAG, "SSID: " + mSSID);

            if(mUsername != null)
            {
                Log.d(TAG, "Username: " + mUsername);
            }
            if(mPasswordType != null && mPassword != null)
            {
                Log.d(TAG, "Password Type: " + mPasswordType);
                Log.d(TAG, "Password: " + mPassword);
            }
            if (mIP != null)
            {
                Log.d(TAG, "Static ip: " + mIP);
                Log.d(TAG, "Static gateway: " + mGateway);
                Log.d(TAG, "Static prefix: " + mPrefix);
                Log.d(TAG, "Static dns1: " + mDns1);
                Log.d(TAG, "Static dns2: " + mDns2);
            }
            textview.setText(R.string.trying_to_connect_to);

            // Setup broadcast receiver
            broadcastReceiver = new CheckSSIDBroadcastReceiver(mSSID);
            broadcastReceiver.setSSIDFoundListener(this);

            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(broadcastReceiver, filter);

            // Set wifi config
            WifiEnabled();

            return;
        }
        printUsage();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (broadcastReceiver != null)
        unregisterReceiver(broadcastReceiver);
        broadcastReceiver = null;
    }

    @Override
    public void SSIDFound()
    {
        Log.d(TAG, "Device Connected to " + mSSID);
        if (mThread != null)
        {
            mThread.interrupt();
            try
            {
                mThread.join();
            }
            catch (InterruptedException e)
            {
                Log.e(TAG, "Hit exception", e);
            }
        }
        finish();
    }

    @Override
    public void WifiEnabled()
    {
        Log.d(TAG, "WifiEnabled");
        if (mThread != null)
            return;

        WifiConfiguration wfc = getExistingWifiConfiguration();
        int networkId;

        if (wfc != null)
            mWifiManager.removeNetwork(wfc.networkId);

        // Wifi configuration didn't exist for this SSID, create it.
        wfc = new WifiConfiguration();
        updateWifiConfiguration(wfc);
        networkId = mWifiManager.addNetwork(wfc);

        if (networkId == -1)
        {
            Log.d(TAG, "Invalid wifi network (ensure this SSID exists, auth method and password are correct, etc.)");
            finish();
            return;
        }

        final int finalNetworkId = networkId;

        mThread = new Thread() {
            @Override
            public void run() {
                mWifiManager.disconnect();
                try
                {
                    while(!isInterrupted())
                    {
                        Log.d(TAG, "Joining, network id=" + Integer.toString(finalNetworkId));
                        mWifiManager.enableNetwork(finalNetworkId, true);
                        mWifiManager.reconnect();
                        // Wait and see if it worked. Otherwise try again.
                        sleep(10000);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        };
        mThread.start();
    }

    private boolean permittedToUpdate(WifiConfiguration wfc)
    {
        Field field;
        int creatorUid;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try
            {
                field = wfc.getClass().getDeclaredField("creatorUid");
                creatorUid = field.getInt(wfc);
            }
            catch (ReflectiveOperationException e)
            {
                Log.e(TAG, "Hit exception", e);
                return false;
            }
            if (creatorUid == getApplicationInfo().uid || canEditWifi())
            {
                Log.d(TAG, "App is permitted to modify this wifi configuration");
                return true;
            }
        }
        // Since app doesn't have proper permissions, we will join the existing Wifi network as configured
        Log.w(TAG, "App does not have admin access, unable to modify a wifi network created by another app");
        return false;
    }

    private void updateWifiConfiguration(WifiConfiguration wfc)
    {
        wfc.SSID = "\"".concat(mSSID).concat("\"");
        wfc.status = WifiConfiguration.Status.ENABLED;
        wfc.priority = 100;
        if (mPasswordType == null) // no password
        {
            wfc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            wfc.allowedAuthAlgorithms.clear();
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        }
        else if (mPasswordType.equals(WEP_PASSWORD)) // WEP
        {
            wfc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            wfc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            wfc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);

            // if hex string
            // wfc.wepKeys[0] = password;

            wfc.wepKeys[0] = "\"".concat(mPassword).concat("\"");
            wfc.wepTxKeyIndex = 0;
        }
        else if (mPasswordType.equals(WPA_PASSWORD)) // WPA(2)
        {
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            wfc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);

            wfc.preSharedKey = "\"".concat(mPassword).concat("\"");
        }
        else if (mPasswordType.equals(PEAP_PASSWORD)) // 802.1x with PEAP
        {
            WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
            wfc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
            wfc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);
            enterpriseConfig.setIdentity(mUsername);
            enterpriseConfig.setPassword(mPassword);
            enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.PEAP);
            enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2);
            wfc.enterpriseConfig = enterpriseConfig;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                Proxy.setProxy(wfc, mProxyInfo);
            } catch (IllegalArgumentException | ReflectiveOperationException e) {
                Log.e(TAG, "Failed to set proxy on wifi configuration", e);
            }
        }

        if (mIP != null) {
            Log.d(TAG, "Setting static ip address.");
            staticIpConfiguration(wfc);
        }
    }

    private WifiConfiguration getExistingWifiConfiguration()
    {
        for( WifiConfiguration i : mWifiManager.getConfiguredNetworks())
        {
            if(i.SSID != null && i.SSID.equals("\"".concat(mSSID).concat("\"")))
            {
                Log.d(TAG, "wifi network already exists.");
                return i;
            }
        }
        return null;
    }

    private void removeAllWifiConfig() {
        List<WifiConfiguration> confs = mWifiManager.getConfiguredNetworks();
        for (WifiConfiguration conf : confs) {
            String ssid = conf.SSID;
            int nwId = conf.networkId;
            if (mWifiManager.removeNetwork(nwId)) {
                Log.d(TAG, "Succesfully remove wifi-config with SSID: " + ssid);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private boolean canEditWifi()
    {
        DevicePolicyManager devicePolicyManager =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        return devicePolicyManager.isAdminActive(new ComponentName(this, AdminReceiver.class)) &&
               devicePolicyManager.isDeviceOwnerApp(getPackageName());
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void clearDeviceOwner()
    {
        if (canEditWifi())
        {
            DevicePolicyManager devicePolicyManager =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

            devicePolicyManager.clearDeviceOwnerApp(getPackageName());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    private void staticIpConfiguration(WifiConfiguration wfc) {
        try {
            Class<?> ipAssignment = wfc.getClass().getMethod("getIpAssignment").invoke(wfc).getClass();
            Object staticConf = wfc.getClass().getMethod("getStaticIpConfiguration").invoke(wfc);

            wfc.getClass().getMethod("setIpAssignment", ipAssignment).invoke(wfc, Enum.valueOf((Class<Enum>) ipAssignment, "STATIC"));
            if (staticConf == null) {
                Class<?> staticConfigClass = Class.forName("android.net.StaticIpConfiguration");
                staticConf = staticConfigClass.newInstance();
            }
            // STATIC IP AND MASK PREFIX
            Constructor<?> laConstructor = LinkAddress.class.getConstructor(InetAddress.class, int.class);
            LinkAddress linkAddress = (LinkAddress) laConstructor.newInstance(
                    InetAddress.getByName(mIP),
                    mPrefix);
            staticConf.getClass().getField("ipAddress").set(staticConf, linkAddress);
            // GATEWAY
            staticConf.getClass().getField("gateway").set(staticConf, InetAddress.getByName(mGateway));
            // DNS
            List<InetAddress> dnsServers = (List<InetAddress>) staticConf.getClass().getField("dnsServers").get(staticConf);
            dnsServers.clear();
            dnsServers.add(InetAddress.getByName(mDns1));
            dnsServers.add(InetAddress.getByName(mDns2)); // Google DNS as DNS2 for safety
            // apply the new static configuration
            wfc.getClass().getMethod("setStaticIpConfiguration", staticConf.getClass()).invoke(wfc, staticConf);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
