package app.truemotion.vector;

/** Geometry for a planar, non-stretching two-link chain. Android-independent. */
final class RigMath {
    private RigMath() {}
    static float[] solve(double dx,double dy,double first,double second,boolean bendOtherSide) {
        if(!finite(dx)||!finite(dy)||!finite(first)||!finite(second)||first<=0||second<=0)
            throw new IllegalArgumentException("Invalid chain coordinates or lengths");
        double distance=Math.hypot(dx,dy),maximum=first+second,minimum=Math.abs(first-second);
        double reach=Math.max(minimum,Math.min(maximum,distance));
        // Keep the direction of an unreachable target; only the distance clamps.
        double direction=distance>1e-9?Math.atan2(dy,dx):0;
        double cosine=Math.max(-1,Math.min(1,(reach*reach-first*first-second*second)/(2*first*second)));
        double elbow=Math.acos(cosine)*(bendOtherSide?-1:1);
        double shoulder=direction-Math.atan2(second*Math.sin(elbow),first+second*Math.cos(elbow));
        return new float[]{(float)Math.toDegrees(shoulder),(float)Math.toDegrees(elbow),(float)reach};
    }
    static float nearest(float degrees,float reference) {
        return degrees+360f*Math.round((reference-degrees)/360f);
    }
    private static boolean finite(double value) {return !Double.isNaN(value)&&!Double.isInfinite(value);}
}
