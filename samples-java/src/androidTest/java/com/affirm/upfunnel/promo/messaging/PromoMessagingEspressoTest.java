package com.affirm.android;

import android.app.Activity;
import android.os.SystemClock;
import android.webkit.WebView;

import com.affirm.samples.Config;
import com.affirm.samples.MainActivity;
import com.affirm.samples.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import androidx.test.espresso.web.webdriver.Locator;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.espresso.web.assertion.WebViewAssertions.webMatches;
import static androidx.test.espresso.web.model.Atoms.getText;
import static androidx.test.espresso.web.sugar.Web.onWebView;
import static androidx.test.espresso.web.webdriver.DriverAtoms.findElement;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class PromoMessagingEspressoTest {

    private static final int PROMO_REQUEST_COUNT = 4;
    private static final long WAIT_TIMEOUT_MS = 10_000L;
    private static final String PROMO_EXTERNAL_ID = "promo-messaging-test";

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

    private MockWebServer mockWebServer;

    @Before
    public void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        AffirmPlugins.setPromoBaseUrlOverride(mockWebServer.url("").toString());
    }

    @After
    public void tearDown() throws IOException {
        finishResumedActivities();
        activityRule.finishActivity();
        AffirmPlugins.clearPromoBaseUrlOverride();
        mockWebServer.shutdown();
    }

    @Test
    public void rendersNativeAndHtmlPromoFromFixtureAndSendsExpectedRequests() throws Exception {
        launchWithPromoFixture("promo_adaptive.json");

        eventually(() -> onView(withId(R.id.promotionTextView))
                .check(matches(isDisplayed()))
                .check(matches(withText(startsWith("As low as $60/month"))))
                .check(matches(withContentDescription(
                        "As low as $60/month at 0% APR. Learn more"))));

        eventually(() -> onView(withId(R.id.promo))
                .check(matches(isDisplayed()))
                .check(matches(hasDescendant(withContentDescription(
                        "As low as $60/month at 0% APR. Learn more")))));

        eventually(() -> onWebView(withId(R.id.htmlPromotionWebView))
                .withElement(findElement(Locator.CLASS_NAME, "affirm-modal-trigger"))
                .check(webMatches(getText(), equalTo("Learn more"))));

        eventually(() -> onWebView(withId(R.id.htmlPromotionWebView))
                .withElement(findElement(Locator.CLASS_NAME, "affirm-ala-price"))
                .check(webMatches(getText(), equalTo("$60"))));

        captureScreenshot("native-html-ala-rendered");

        for (int i = 0; i < PROMO_REQUEST_COUNT; i++) {
            assertExpectedPromoRequest(takePromoRequest());
        }
    }

    @Test
    public void hidesPromoWhenResponseIsEmpty() throws Exception {
        launchWithPromoFixture("promo_empty.json");

        eventually(() -> onView(withId(R.id.promo))
                .check(matches(withEffectiveVisibility(ViewMatchers.Visibility.GONE))));
        eventually(() -> onView(withId(R.id.promotionTextView))
                .check(matches(withText(""))));
        captureScreenshot("empty-promo-hidden");
    }

    @Test
    public void tapAdaptivePromoStartsPrequalActivity() throws Exception {
        launchWithPromoFixture("promo_adaptive.json");

        eventually(() -> onView(withId(R.id.promo)).check(matches(isDisplayed())));
        onView(withId(R.id.promo)).perform(click());

        eventually(() -> assertThat(getResumedActivity(), instanceOf(PrequalActivity.class)));
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertNotNull(device.wait(Until.findObject(By.clazz(WebView.class)), WAIT_TIMEOUT_MS));
        captureScreenshot("adaptive-promo-prequal-presented");
    }

    @Test
    public void tapFastPromoStartsModalActivity() throws Exception {
        launchWithPromoFixture("promo_fast.json");

        eventually(() -> onView(withId(R.id.promo)).check(matches(isDisplayed())));
        onView(withId(R.id.promo)).perform(click());

        eventually(() -> assertThat(getResumedActivity(), instanceOf(ModalActivity.class)));
        captureScreenshot("fast-promo-modal-presented");
    }

    private void launchWithPromoFixture(String fixtureName) throws IOException {
        String fixture = readFixture(fixtureName);
        for (int i = 0; i < PROMO_REQUEST_COUNT; i++) {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(fixture));
        }
        activityRule.launchActivity(null);
    }

    private RecordedRequest takePromoRequest() throws InterruptedException {
        RecordedRequest request = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull("Expected promo request", request);
        return request;
    }

    private void assertExpectedPromoRequest(RecordedRequest request) {
        HttpUrl url = request.getRequestUrl();
        assertNotNull(url);
        assertEquals("/api/promos/v2/" + Config.PUBLIC_KEY, url.encodedPath());
        assertEquals("true", url.queryParameter("is_sdk"));
        assertEquals("ala", url.queryParameter("field"));
        assertEquals("110000", url.queryParameter("amount"));
        assertEquals("true", url.queryParameter("show_cta"));
        assertEquals(PROMO_EXTERNAL_ID, url.queryParameter("promo_external_id"));
        assertEquals("product", url.queryParameter("page_type"));
        assertEquals("blue", url.queryParameter("logo_color"));
        assertEquals("logo", url.queryParameter("logo_type"));
        assertEquals("en_GB", url.queryParameter("locale"));
        assertEquals("Affirm-Android-SDK", request.getHeader("Affirm-User-Agent"));
        assertNotNull(request.getHeader("Affirm-User-Agent-Version"));

        String items = url.queryParameter("items");
        assertNotNull(items);
        assertTrue(items.contains("Great Deal Wheel"));
        assertTrue(items.contains("\"sku\":\"wheel\""));
    }

    private String readFixture(String fixtureName) throws IOException {
        InputStream inputStream = InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("promos/upfunnel/" + fixtureName);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
            return builder.toString();
        } finally {
            inputStream.close();
        }
    }

    private Activity getResumedActivity() {
        final Activity[] resumedActivity = new Activity[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            if (!activities.isEmpty()) {
                resumedActivity[0] = activities.iterator().next();
            }
        });
        return resumedActivity[0];
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
