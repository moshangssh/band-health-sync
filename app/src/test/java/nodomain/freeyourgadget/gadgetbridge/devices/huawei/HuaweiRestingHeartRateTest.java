package nodomain.freeyourgadget.gadgetbridge.devices.huawei;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HuaweiRestingHeartRateTest extends TestBase {

    private GBDevice device;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        device = createDummyGDevice("00:00:00:00:20");
    }

    private HuaweiActivitySample seedRestingHeartRate(HuaweiSampleProvider provider, int restingHeartRate) {
        User user = DBHelper.getUser(daoSession);
        Device dbDevice = DBHelper.getDevice(device, daoSession);

        HuaweiActivitySample raw = provider.createActivitySample();
        raw.setProvider(provider);
        raw.setRawKind(1);
        raw.setTimestamp(1080);
        raw.setOtherTimestamp(1140);
        raw.setRawIntensity(10);
        raw.setHeartRate(70);
        raw.setSteps(100);
        raw.setRestingHeartRate(restingHeartRate);
        raw.setUserId(user.getId());
        raw.setDeviceId(dbDevice.getId());
        provider.addGBActivitySamples(Collections.singletonList(raw));
        return raw;
    }

    @Test
    public void restingHeartRateIsStoredOnTheRawRow() {
        HuaweiSampleProvider provider = new HuaweiSampleProvider(device, daoSession);
        seedRestingHeartRate(provider, 55);

        List<HuaweiActivitySample> raw = provider.getAllActivitySamplesHighRes(1080, 1140);
        assertEquals(1, raw.size());
        assertEquals(55, raw.get(0).getRestingHeartRate());
    }

    @Test
    public void restingHeartRateSurvivesTheActivitySampleRead() {
        HuaweiSampleProvider provider = new HuaweiSampleProvider(device, daoSession);
        seedRestingHeartRate(provider, 55);

        List<HuaweiActivitySample> processed = provider.getAllActivitySamples(1000, 1200);

        assertTrue(
                "resting heart rate must reach the samples the self-hosted sync reads",
                processed.stream().anyMatch(s -> s.getRestingHeartRate() > 0)
        );
    }
}
