package me.whish.emotify.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PointerButtonsTest {
    @Test
    void mapsVanillaButtonsToThePortableIndices() {
        assertEquals(PointerButtons.PRIMARY, portableIndex(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(PointerButtons.SECONDARY, portableIndex(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(PointerButtons.MIDDLE, portableIndex(InputConstants.MOUSE_BUTTON_MIDDLE));
    }

    @Test
    void neverReportsAnUnrelatedButtonAsPrimary() {
        assertEquals(PointerButtons.UNKNOWN, portableIndex(0));
        assertEquals(PointerButtons.UNKNOWN, portableIndex(InputConstants.MOUSE_BUTTON_4));
    }

    private static int portableIndex(int button) {
        return PointerButtons.INSTANCE.portableIndex(new MouseButtonEvent(0.0, 0.0, new MouseButtonInfo(button, 0)));
    }
}
