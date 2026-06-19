package com.affirm.android;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import com.affirm.samples.MainActivity;
import com.affirm.samples.R;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collection;

import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.UiDevice;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class PromoMessagingThorEspressoTest {

    private static final String ARG_PROMO_BASE_URL = "affirmPromoBaseUrl";
    private static final String ARG_PUBLIC_KEY = "affirmPublicKey";
    private static final String ARG_EXPECTED_PROMO_TEXT = "affirmExpectedPromoText";
    private static final String ARG_COUNTRY_CODE = "affirmCountryCode";
    private static final String ARG_LOCALE = "affirmLocale";
    private static final String ARG_PROMO_EXTERNAL_ID = "affirmPromoExternalId";
    private static final String UPFUNNEL_PROMO_TEST_PREFERENCES = "upfunnel_promo_test";
    private static final String PROMO_EXTERNAL_ID_PREFERENCE = "promo_external_id";
    private static final String DEFAULT_EXPECTED_PROMO_TEXT = "Affirm";
    private static final String DEFAULT_PROMO_EXTERNAL_ID = "test_external_id";
    private static final long WAIT_TIMEOUT_MS = 15_000L;

    @Rule
    public ActivityTestRule<MainActivity> activityRule =
            new ActivityTestRule<>(MainActivity.class, true, false);

    @Rule
    public TestWatcher screenshotRule = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
            captureScreenshot(description.getMethodName() + "-failure");
        }
    };

    private String expectedPromoText;

    @Before
    public void setUp() {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String promoBaseUrl = arguments.getString(ARG_PROMO_BASE_URL);
        String publicKey = arguments.getString(ARG_PUBLIC_KEY);
        expectedPromoText = valueOrDefault(arguments.getString(ARG_EXPECTED_PROMO_TEXT), DEFAULT_EXPECTED_PROMO_TEXT);

        Assume.assumeTrue(
                "Thor promo messaging test requires instrumentation arguments: " +
                        ARG_PROMO_BASE_URL + " and " + ARG_PUBLIC_KEY,
                !isBlank(promoBaseUrl) && !isBlank(publicKey));

        AffirmPlugins.setPromoBaseUrlOverride(promoBaseUrl);
        Affirm.setPublicKey(publicKey);
        Affirm.setCountryCode(valueOrDefault(arguments.getString(ARG_COUNTRY_CODE), "USA"));
        Affirm.setLocale(valueOrDefault(arguments.getString(ARG_LOCALE), "en_US"));
        setPromoExternalId(valueOrDefault(arguments.getString(ARG_PROMO_EXTERNAL_ID), DEFAULT_PROMO_EXTERNAL_ID));
    }

    @After
    public void tearDown() {
        finishResumedActivities();
        activityRule.finishActivity();
        clearPromoExternalId();
        AffirmPlugins.clearPromoBaseUrlOverride();
    }

    @Test
    public void rendersPromoMessagingFromThorService() throws Exception {
        activityRule.launchActivity(null);

        eventually(() -> onView(withId(R.id.promotionTextView))
                .check(matches(isDisplayed()))
                .check(matches(not(withText(""))))
                .check(matches(withText(containsString(expectedPromoText))))
                .check(matches(withContentDescription(containsString(expectedPromoText)))));

        eventually(() -> onView(withId(R.id.promo))
                .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)))
                .check(matches(hasDescendant(withContentDescription(containsString(expectedPromoText))))));

        captureScreenshot("thor-promo-rendered");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private void setPromoExternalId(String promoExternalId) {
        InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getSharedPreferences(UPFUNNEL_PROMO_TEST_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(PROMO_EXTERNAL_ID_PREFERENCE, promoExternalId)
                .commit();
    }

    private void clearPromoExternalId() {
        InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getSharedPreferences(UPFUNNEL_PROMO_TEST_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(PROMO_EXTERNAL_ID_PREFERENCE)
                .commit();
    }

    private void finishResumedActivities() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for (Activity activity : activities) {
                activity.finish();
            }
        });
    }

    private void eventually(ThrowingRunnable assertion) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        Throwable lastFailure = null;
        do {
            try {
                assertion.run();
                return;
            } catch (Throwable throwable) {
                lastFailure = throwable;
                SystemClock.sleep(100);
            }
        } while (SystemClock.uptimeMillis() < deadline);

        if (lastFailure instanceof Exception) {
            throw (Exception) lastFailure;
        }
        if (lastFailure instanceof AssertionError) {
            throw (AssertionError) lastFailure;
        }
        throw new AssertionError(lastFailure);
    }

    private void captureScreenshot(String name) {
        File screenshotDir = InstrumentationRegistry
                .getInstrumentation()
                .getTargetContext()
                .getExternalFilesDir("upfunnel-promo-screenshots");
        if (screenshotDir == null) {
            return;
        }
        if (!screenshotDir.exists() && !screenshotDir.mkdirs()) {
            return;
        }
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.takeScreenshot(new File(screenshotDir, name + ".png"));
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
