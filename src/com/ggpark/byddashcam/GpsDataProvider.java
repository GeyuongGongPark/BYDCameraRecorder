package com.ggpark.byddashcam;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

/**
 * GPS 위치 데이터 공급자.
 * LocationManager를 래핑하고 최근 fix를 캐싱합니다.
 */
public class GpsDataProvider implements LocationListener {
    public interface Listener {
        void onFixUpdated(GpsFix fix);
    }
    private static final String TAG = "BYDCamera";
    private static final long UPDATE_INTERVAL_MS = 1000L;
    private static final float UPDATE_MIN_DISTANCE_M = 0f;
    private static final long MAX_FIX_AGE_MS = 5000L;
    private static final double MS_TO_KMH = 3.6;

    private volatile GpsFix lastFix = GpsFix.UNAVAILABLE;
    private LocationManager locationManager;
    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start(Context context) {
        locationManager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Log.w(TAG, "GPS: LocationManager unavailable");
            return;
        }
        try {
            boolean anyStarted = false;
            for (String provider : new String[]{
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (locationManager.isProviderEnabled(provider)) {
                        locationManager.requestLocationUpdates(
                                provider,
                                UPDATE_INTERVAL_MS,
                                UPDATE_MIN_DISTANCE_M,
                                this);
                        Location lastKnown =
                                locationManager.getLastKnownLocation(provider);
                        if (lastKnown != null) {
                            updateFromLocation(lastKnown);
                        }
                        Log.i(TAG, "GPS: started provider=" + provider);
                        anyStarted = true;
                    } else {
                        Log.w(TAG, "GPS: provider disabled=" + provider);
                    }
                } catch (SecurityException e) {
                    Log.w(TAG, "GPS: no permission for provider=" + provider);
                }
            }
            if (!anyStarted) {
                Log.w(TAG, "GPS: no providers available");
            }
        } catch (Exception exception) {
            Log.w(TAG, "GPS: start failed", exception);
        }
    }

    public void stop() {
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
            }
            locationManager = null;
        }
        lastFix = GpsFix.UNAVAILABLE;
    }

    /** 가장 최근 GPS fix를 반환합니다. fix가 없으면 GpsFix.UNAVAILABLE. */
    public GpsFix getLastFix() {
        return lastFix;
    }

    @Override
    public void onLocationChanged(Location location) {
        updateFromLocation(location);
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i(TAG, "GPS provider enabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.w(TAG, "GPS provider disabled: " + provider);
        lastFix = GpsFix.UNAVAILABLE;
    }

    private void updateFromLocation(Location location) {
        long now = System.currentTimeMillis();
        // 일부 기기에서 location.getTime()이 0을 반환할 수 있음 → 현재 시각으로 대체
        long fixTime = location.getTime() > 0 ? location.getTime() : now;
        boolean fresh = (now - fixTime) < MAX_FIX_AGE_MS;
        double speedKmh =
                location.hasSpeed()
                        ? location.getSpeed() * MS_TO_KMH
                        : 0.0;
        lastFix = new GpsFix(
                speedKmh,
                location.getLatitude(),
                location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : 0.0,
                fixTime,
                fresh);
        Listener l = listener;
        if (l != null) {
            l.onFixUpdated(lastFix);
        }
    }
}
