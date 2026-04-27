package com.affirm.android;

import android.text.SpannableString;
import android.text.style.ImageSpan;

import com.google.common.collect.ImmutableMap;
import com.google.common.truth.Truth;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.math.BigDecimal;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class AffirmUtilsTest {

    @Test
    public void convertToAffirmAmounts() {
        Truth.assertThat(AffirmUtils.decimalDollarsToIntegerCents(BigDecimal.valueOf(15.5))).isEqualTo(1550);
        Truth.assertThat(AffirmUtils.decimalDollarsToIntegerCents(BigDecimal.valueOf(15.5492))).isEqualTo(1554);
        Truth.assertThat(AffirmUtils.decimalDollarsToIntegerCents(BigDecimal.valueOf(3.0))).isEqualTo(300);
    }

    @Test
    public void replacePlaceHolders() {
        Map<String, String> map = ImmutableMap.of("money", "55", "name", "jan", "day", "monday");
        String text = "I paid {{money}} to {{name}} last {{day}}";

        Truth.assertThat(AffirmUtils.replacePlaceholders(text, map))
                .contains("I paid 55 to jan last monday");
    }

    @Test
    public void createSpannable_onlyReplacesPlaceholderWithLogo() {
        // Template contains one {affirm_logo} placeholder and one plain text "Affirm"
        String template = "Pay over time with {affirm_logo}. Affirm is a form of credit.";

        SpannableString result = AffirmUtils.createSpannableForText(
                template,
                14f,
                AffirmLogoType.AFFIRM_DISPLAY_TYPE_LOGO,
                AffirmColor.AFFIRM_COLOR_TYPE_BLUE,
                RuntimeEnvironment.getApplication()
        );

        // Only 1 ImageSpan should be present (for the placeholder), not 2
        ImageSpan[] spans = result.getSpans(0, result.length(), ImageSpan.class);
        Truth.assertThat(spans).hasLength(1);

        // The plain text "Affirm" should remain as text in the output
        String resultText = result.toString();
        Truth.assertThat(resultText).contains("Affirm is a form of credit.");
    }

    @Test
    public void createSpannable_plainTextAffirmNotReplacedWithLogo() {
        // Template with ONLY plain text "Affirm" and no placeholder
        String template = "Affirm offers great financing options.";

        SpannableString result = AffirmUtils.createSpannableForText(
                template,
                14f,
                AffirmLogoType.AFFIRM_DISPLAY_TYPE_LOGO,
                AffirmColor.AFFIRM_COLOR_TYPE_BLUE,
                RuntimeEnvironment.getApplication()
        );

        // No ImageSpan should be present since there is no {affirm_logo} placeholder
        ImageSpan[] spans = result.getSpans(0, result.length(), ImageSpan.class);
        Truth.assertThat(spans).hasLength(0);

        // The text should remain unchanged
        Truth.assertThat(result.toString()).isEqualTo(template);
    }

    @Test
    public void createSpannable_multiplePlaceholdersAllReplaced() {
        // Template with multiple {affirm_logo} placeholders
        String template = "Pay with {affirm_logo} or learn about {affirm_logo} financing.";

        SpannableString result = AffirmUtils.createSpannableForText(
                template,
                14f,
                AffirmLogoType.AFFIRM_DISPLAY_TYPE_LOGO,
                AffirmColor.AFFIRM_COLOR_TYPE_BLUE,
                RuntimeEnvironment.getApplication()
        );

        // Both placeholders should get replaced with logo ImageSpans
        ImageSpan[] spans = result.getSpans(0, result.length(), ImageSpan.class);
        Truth.assertThat(spans).hasLength(2);
    }

    @Test
    public void createSpannable_textDisplayType_noLogosInserted() {
        // When display type is TEXT, no logos should be inserted even with placeholders
        String template = "Pay over time with {affirm_logo}.";

        SpannableString result = AffirmUtils.createSpannableForText(
                template,
                14f,
                AffirmLogoType.AFFIRM_DISPLAY_TYPE_TEXT,
                AffirmColor.AFFIRM_COLOR_TYPE_BLUE,
                RuntimeEnvironment.getApplication()
        );

        ImageSpan[] spans = result.getSpans(0, result.length(), ImageSpan.class);
        Truth.assertThat(spans).hasLength(0);
    }
}
