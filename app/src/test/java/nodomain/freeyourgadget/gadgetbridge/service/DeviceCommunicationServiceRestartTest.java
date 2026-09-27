package nodomain.freeyourgadget.gadgetbridge.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Service;
import android.content.Intent;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;

import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class DeviceCommunicationServiceRestartTest {
    @Test
    public void stickyRestartRestoresAutomaticConnections() {
        final TestService service = new TestService();
        service.scheduleReconnectAfterServiceCreation();

        final int result = service.onStartCommand(null, 0, 1);

        assertEquals(Service.START_STICKY, result);
        assertEquals(1, service.reconnectRequests);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DeviceCommunicationService.SERVICE_CREATION_RECONNECT_DELAY_MS));
        assertEquals(1, service.reconnectRequests);
    }

    @Test
    public void serviceCreationWithoutStartCommandRestoresAutomaticConnections() {
        final TestService service = new TestService();
        service.scheduleReconnectAfterServiceCreation();

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DeviceCommunicationService.SERVICE_CREATION_RECONNECT_DELAY_MS - 1));
        assertEquals(0, service.reconnectRequests);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertEquals(1, service.reconnectRequests);
    }

    @Test
    public void explicitIntentWithoutActionDoesNotLookLikeStickyRestart() {
        final TestService service = new TestService();

        final int result = service.onStartCommand(new Intent(), 0, 1);

        assertEquals(Service.START_STICKY, result);
        assertEquals(0, service.reconnectRequests);
    }

    @Test
    public void cancelledFallbackDoesNotReconnect() {
        final TestService service = new TestService();
        service.scheduleReconnectAfterServiceCreation();
        service.cancelReconnectAfterServiceCreation();

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DeviceCommunicationService.SERVICE_CREATION_RECONNECT_DELAY_MS));
        assertEquals(0, service.reconnectRequests);
    }

    @Test
    public void connectRequestKeepsFallbackForOtherDevices() {
        assertFalse(DeviceCommunicationService.cancelsReconnectFallback(DeviceService.ACTION_CONNECT));
    }

    @Test
    public void disconnectRequestCancelsFallback() {
        assertTrue(DeviceCommunicationService.cancelsReconnectFallback(DeviceService.ACTION_DISCONNECT));
    }

    private static final class TestService extends DeviceCommunicationService {
        private int reconnectRequests;

        @Override
        protected void reconnectAfterServiceRestart() {
            reconnectRequests++;
        }
    }
}
