import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public final class CardChromeClassificationSmoke {
    static class NanoTemplateCardView extends FrameLayout { NanoTemplateCardView() { super(null); } }
    static class PersonalHeaderCardView extends LinearLayout { PersonalHeaderCardView() { super(null); } }
    static class CardView extends FrameLayout { CardView() { super(null); } }
    static class PlainSubclass extends androidx.cardview.widget.CardView { }
    public static void main(String[] args) throws Exception {
        var method = View.class.getDeclaredMethod("macapkIsCardWidget");
        method.setAccessible(true);
        for (View custom : new View[]{new NanoTemplateCardView(), new PersonalHeaderCardView(),
                new CardView(), new FrameLayout(null)}) {
            if ((Boolean)method.invoke(custom)) throw new AssertionError("custom container received synthetic card chrome");
        }
        for (View card : new View[]{new androidx.cardview.widget.CardView(),
                new com.google.android.material.card.MaterialCardView(), new PlainSubclass()}) {
            if (!(Boolean)method.invoke(card)) throw new AssertionError("actual card inheritance lost fallback");
        }
        System.out.println("card chrome: custom name collisions rejected, actual card types and inherited subclasses preserved");
    }
}
