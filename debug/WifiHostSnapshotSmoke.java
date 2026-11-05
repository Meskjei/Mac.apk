package android.net.wifi;

import org.json.JSONObject;

public final class WifiHostSnapshotSmoke {
    public static void main(String[] args) throws Exception {
        var empty = new WifiInfo(new JSONObject());
        if (!empty.getBSSID().isEmpty() || empty.getRssi() != WifiInfo.INVALID_RSSI
                || empty.getFrequency() != 0 || empty.getSupplicantState() != SupplicantState.UNINITIALIZED)
            throw new AssertionError("Unavailable Wi-Fi must not look like a real connection");
        if (new WifiInfo(new JSONObject("{\"powered\":false}")).getSupplicantState() != SupplicantState.DISCONNECTED)
            throw new AssertionError("Radio off");
        var snapshot = new JSONObject("{\"ssid\":\"fixture\",\"bssid\":\"02:11:22:33:44:55\",\"rssi\":-43,\"linkSpeed\":144,\"frequency\":5180,\"networks\":[{\"ssid\":\"fixture\",\"bssid\":\"02:11:22:33:44:55\",\"rssi\":-43,\"frequency\":5180},null,{\"ssid\":\"redacted\"}]}");
        var info = new WifiInfo(snapshot);
        if (!info.getSSID().equals("\"fixture\"") || !info.getBSSID().equals("02:11:22:33:44:55")
                || info.getRssi() != -43 || info.getLinkSpeed() != 144 || info.getFrequency() != 5180
                || info.getSupplicantState() != SupplicantState.COMPLETED
                || !info.getMacAddress().equals("02:00:00:00:00:00")) throw new AssertionError("Host connection fields");
        var scans = WifiManager.scanResults(snapshot);
        if (scans.size() != 1 || scans.get(0).frequency != 5180 || scans.get(0).level != -43
                || scans.get(0).timestamp != 0 || scans.get(0).channelWidth != ScanResult.UNSPECIFIED
                || !scans.get(0).capabilities.isEmpty())
            throw new AssertionError("Use observed fields; do not fabricate scan times or security capabilities");
        if (!WifiManager.scanResults(new JSONObject()).isEmpty()) throw new AssertionError("No scan cache");
        if (com.macapk.loader.LocationBridge.wifiSnapshot().length() != 0)
            throw new AssertionError("No application/permission must not expose the host");
        var caps = new android.net.NetworkCapabilities();
        var transport = caps.getTransportInfo();
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
            if (!(transport instanceof WifiInfo) || !((WifiInfo) transport).getBSSID().isEmpty())
                throw new AssertionError("Modern API must use the same redacted Wi-Fi view");
        } else if (transport != null) throw new AssertionError("Non-Wi-Fi transport");
        System.out.println("Wi-Fi host snapshot, redaction and unknown-field checks passed");
    }
}
