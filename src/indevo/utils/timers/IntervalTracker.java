package indevo.utils.timers;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.util.IntervalUtil;

public class IntervalTracker implements EveryFrameScript {

    private static IntervalUtil interval = new IntervalUtil(1f, 1f);
    private static IntervalUtil interval2s = new IntervalUtil(2f, 2f);

    @Override
    public void advance(float amount) {
        interval.advance(amount);
        interval2s.advance(amount);

        //bla bla
    }

    public IntervalTracker() {
        interval = new IntervalUtil(1f, 1f);
        interval2s = new IntervalUtil(2f, 2f);
    }

    public float getIntervalFraction1s() {
        return Math.min(interval.getElapsed() / interval.getIntervalDuration(), 1f);
    }

    public boolean isElapsed1s() {
        return interval.intervalElapsed();
    }

    public float getIntervalFraction2s() {
        return Math.min(interval2s.getElapsed() / interval2s.getIntervalDuration(), 1f);
    }

    public boolean isElapsed2s() {
        return interval2s.intervalElapsed();
    }

    //transient
    public static IntervalTracker getInstance() {
        for (EveryFrameScript transientScript : Global.getSector().getTransientScripts()) {
            if (transientScript instanceof IntervalTracker) {
                return (IntervalTracker) transientScript;
            }
        }

        IntervalTracker script = new IntervalTracker();
        Global.getSector().addTransientScript(script);
        return script;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }
}
