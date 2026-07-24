# Railway Sign Free Text Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give each Railway Sign slot block-owned free text rendered like Exit destination text without an Exit Character or any Station Exit ownership.

**Architecture:** Add a small per-slot text data object owned by `BlockRailwaySign.BlockEntity`, append it to the existing configuration packet, expose two transparent built-in sign resources for alignment, and route them through a Station-independent render branch.

**Tech Stack:** Java 17, Minecraft mapped block entities/NBT/packets/widgets, MTR dynamic text textures, JUnit 5, Gradle Fabric module.

---

### Task 1: Per-slot text data

**Files:**
- Create: `fabric/src/main/java/org/mtr/mod/block/RailwaySignTextData.java`
- Test: `fabric/src/test/java/org/mtr/mod/block/RailwaySignTextDataTest.java`

- [ ] **Step 1: Write failing tests for ownership and lifecycle**

Test two independent instances, per-slot ordering, Unicode and `|`, 1024-character truncation, old empty NBT, NBT round trip, mismatched-array atomic rejection, clearing on a non-free sign, and preservation between `free_text` and `free_text_flipped`.

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.block.RailwaySignTextDataTest`

Expected: FAIL because `RailwaySignTextData` does not exist.

- [ ] **Step 3: Implement the data object**

Use these identifiers and bounds:

```java
public static final String FREE_TEXT_SIGN_ID = "free_text";
public static final String FREE_TEXT_FLIPPED_SIGN_ID = "free_text_flipped";
public static final int MAX_TEXT_LENGTH = 1024;

public static boolean isFreeText(@Nullable String signId) {
	return FREE_TEXT_SIGN_ID.equals(signId) || FREE_TEXT_FLIPPED_SIGN_ID.equals(signId);
}
```

Own a fixed-size `String[]`, sanitize null to empty and truncate to 1024 characters, read/write `custom_text_<index>` NBT keys, and make replacement validate both sign and text array lengths before changing any value.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the focused test class and expect PASS.

### Task 2: Block entity and packet ownership

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/block/BlockRailwaySign.java:170-216`
- Modify: `fabric/src/main/java/org/mtr/mod/packet/PacketUpdateRailwaySignConfig.java:17-70`
- Test: `fabric/src/test/java/org/mtr/mod/block/RailwaySignTextDataTest.java`

- [ ] **Step 1: Add a failing atomic-update test**

Assert invalid sign/text array lengths preserve existing slot text and valid replacement clears non-free slots.

- [ ] **Step 2: Verify RED**

Run the focused test and confirm the new assertion fails.

- [ ] **Step 3: Integrate storage and network data**

Give every Railway Sign block entity its own `RailwaySignTextData`. Read it after sign IDs, write it with the entity NBT, expose `getCustomTexts`, and validate `signIds.length == incomingSignIds.length == incomingCustomTexts.length` before changing `selectedIds` or signs. Append the text count and values after packet sign IDs; cap values again on server application.

- [ ] **Step 4: Verify GREEN**

Run the focused test class and expect PASS.

### Task 3: Built-in palette entries and editor

**Files:**
- Modify: `fabric/src/main/mtr_custom_resources_template.json`
- Modify: `fabric/src/main/resources/assets/mtr/mtr_custom_resources.json`
- Modify: `fabric/src/main/resources/assets/mtr/lang/en_us.json`
- Modify: `fabric/src/main/java/org/mtr/mod/screen/RailwaySignScreen.java`

- [ ] **Step 1: Add the built-in resources**

Add normal and flipped entries using `mtr:textures/block/transparent.png`, `customText: "sign.mtr.free_text"`, `small: true`, and corresponding `flipCustomText` values. Add `"sign.mtr.free_text": "自由文字|Free Text"`.

- [ ] **Step 2: Add the slot-bound text field**

Load the block entity's custom-text array. Add one 1024-character `TextFieldWidgetExtension`, reserve vertical space so it never overlaps the palette, show it only for a free-text slot, and update the active array entry through its changed listener. Tick the field, pass text in the close packet, clear it when the slot is cleared/replaced, and preserve it when only free-text alignment changes.

- [ ] **Step 3: Compile the editor changes**

Run: `./gradlew.bat :fabric:compileJava`

Expected: exit 0.

### Task 4: Exit-style rendering without Exit Character

**Files:**
- Modify: `fabric/src/main/java/org/mtr/mod/render/RenderRailwaySign.java:41-290`
- Test: `fabric/src/test/java/org/mtr/mod/render/RailwaySignFreeTextLayoutTest.java`

- [ ] **Step 1: Write a failing layout test**

Assert normal text begins inside the selected slot and gains right-side empty slots, flipped text mirrors to the left, and usable width includes the selected slot.

- [ ] **Step 2: Run the test and verify RED**

Run: `./gradlew.bat :fabric:test --tests org.mtr.mod.render.RailwaySignFreeTextLayoutTest`

Expected: FAIL because the free-text layout helper does not exist.

- [ ] **Step 3: Implement the dedicated render branch**

Pass the slot custom text into `drawSign`. Before the Exit branch, detect `RailwaySignTextData.isFreeText(signId)`. Do not resolve a Station and do not invoke `getExitSignLetter`. In world rendering call the existing `renderCustomText` with a layout that includes the current slot plus `getMaxWidth` consecutive empty slots. In GUI rendering show actual input, falling back to the resource label in the palette. Never draw the transparent source texture in world space.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run both Railway Sign focused test classes and expect PASS.

### Task 5: Full verification and commit

**Files:** all files above.

- [ ] **Step 1: Run verification**

```powershell
./gradlew.bat :fabric:test
./gradlew.bat :fabric:build
git diff --check
```

Expected: both Gradle commands and diff check exit 0.

- [ ] **Step 2: Commit only Railway Sign changes**

```powershell
git add fabric/src/main/java/org/mtr/mod/block/RailwaySignTextData.java fabric/src/main/java/org/mtr/mod/block/BlockRailwaySign.java fabric/src/main/java/org/mtr/mod/packet/PacketUpdateRailwaySignConfig.java fabric/src/main/java/org/mtr/mod/screen/RailwaySignScreen.java fabric/src/main/java/org/mtr/mod/render/RenderRailwaySign.java fabric/src/main/mtr_custom_resources_template.json fabric/src/main/resources/assets/mtr/mtr_custom_resources.json fabric/src/main/resources/assets/mtr/lang/en_us.json fabric/src/test/java/org/mtr/mod/block/RailwaySignTextDataTest.java fabric/src/test/java/org/mtr/mod/render/RailwaySignFreeTextLayoutTest.java
git commit -m "feat: add railway sign free text"
```
