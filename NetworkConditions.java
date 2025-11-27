import java.util.concurrent.ThreadLocalRandom;

public final class NetworkConditions {

    private static volatile double outboundDropPercent;
    private static volatile double inboundDropPercent;

    private NetworkConditions() {}

    public static void setDropPercent(double percent) {
        setDropPercent(percent, percent);
    }

    public static void setDropPercent(double outboundPercent, double inboundPercent) {
        outboundDropPercent = clampPercent(outboundPercent);
        inboundDropPercent = clampPercent(inboundPercent);
    }

    public static double getOutboundDropPercent() {
        return outboundDropPercent;
    }

    public static double getInboundDropPercent() {
        return inboundDropPercent;
    }

    public static boolean shouldDropOutbound() {
        double rate = outboundDropPercent;
        return rate > 0 && ThreadLocalRandom.current().nextDouble(100.0) < rate;
    }

    public static boolean shouldDropInbound() {
        double rate = inboundDropPercent;
        return rate > 0 && ThreadLocalRandom.current().nextDouble(100.0) < rate;
    }

    private static double clampPercent(double percent) {
        if (percent < 0) {
            return 0;
        }
        if (percent > 100) {
            return 100;
        }
        return percent;
    }
}
