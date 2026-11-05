// android.net.wifi.WifiInfo -- snapshot of the active Wi-Fi connection.
// Wi-Fi identities are populated only by an authorized host snapshot.
package android.net.wifi;

public class WifiInfo implements android.net.TransportInfo {
    /** AOSP WifiInfo sentinel constants. */
    public static final int LINK_SPEED_UNKNOWN = -1;
    public static final int INVALID_RSSI = -127;
    /** AOSP returns this for getSSID() when the SSID is unavailable. */
    public static final String UNKNOWN_SSID = "<unknown ssid>";

    private String ssid = UNKNOWN_SSID, bssid = "";
    private int rssi = INVALID_RSSI, linkSpeed = LINK_SPEED_UNKNOWN, frequency;
    private SupplicantState state = SupplicantState.UNINITIALIZED;
    public WifiInfo() {}
    WifiInfo(org.json.JSONObject snapshot) {
        bssid = snapshot.optString("bssid", "");
        if (snapshot.has("powered") && !snapshot.optBoolean("powered")) state = SupplicantState.DISCONNECTED;
        if (!bssid.isEmpty()) {
            state = SupplicantState.COMPLETED;
            String name = snapshot.optString("ssid", "");
            if (!name.isEmpty()) ssid = "\"" + name + "\"";
            rssi = snapshot.optInt("rssi", INVALID_RSSI);
            linkSpeed = snapshot.optInt("linkSpeed", LINK_SPEED_UNKNOWN);
            frequency = snapshot.optInt("frequency", 0);
        }
    }
    public String getMacAddress()  { return "02:00:00:00:00:00"; }
    public String getBSSID()       { return bssid; }
    // AOSP wraps UTF-8 SSIDs in double-quotes and redacts unavailable identities.
    public String getSSID()        { return ssid; }
    public int    getRssi()        { return rssi; }
    public int    getLinkSpeed()   { return linkSpeed; }     // Mbps
    public int    getTxLinkSpeedMbps() { return LINK_SPEED_UNKNOWN; }
    public int    getRxLinkSpeedMbps() { return LINK_SPEED_UNKNOWN; }
    /** The observed channel frequency in MHz, or 0 if unknown. */
    public int    getFrequency()   { return frequency; }
    public int    getNetworkId()   { return -1; }
    /** AOSP getIpAddress() (deprecated): the IPv4 address as a little-endian
     *  int, 0 if unknown. We don't surface the host IP here -> 0. */
    public int    getIpAddress()   { return 0; }
    public boolean getHiddenSSID() { return false; }
    /** A redacted/unknown connection cannot claim a completed association. */
    public SupplicantState getSupplicantState() { return state; }
}
