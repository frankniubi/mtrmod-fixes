# Railway Sign Free Text Design

## Scope

Add freely editable text to each slot of a Railway Sign. The rendered result uses the existing Exit destination text style and available-space behavior without drawing an Exit Character.

This feature belongs only to Railway Sign block entities. It does not read, create, select, or modify Station Exit information and does not belong to a Station or Station Area.

## Editing

The Railway Sign palette gains built-in `自由文字|Free Text` entries for normal and flipped alignment, matching the two directions available for Exit signs.

Selecting either entry for a slot opens a single-line text field bound to that slot. The field accepts up to 1024 characters, including `|` so users can retain the existing bilingual text behavior. The Railway Sign preview displays the current input.

Choosing a non-free-text sign or clearing the slot clears that slot's free text. Changing between the normal and flipped free-text entries preserves its text. Closing the screen saves all slot values with the rest of the Railway Sign configuration.

## Ownership And Persistence

`BlockRailwaySign.BlockEntity` owns a `customTexts` array with the same length and indices as `signIds`.

Each value is persisted under a per-slot NBT key on that block entity. Missing keys load as empty strings, so existing worlds remain compatible. Values are copied only when the incoming sign and text arrays both match the Railway Sign length.

The existing Railway Sign update packet appends the custom-text array after the sign IDs. The server applies sign IDs and custom text together to the target Railway Sign block entity. No Station ID, Station Area, exit selection, `StationExit`, or `selectedIds` entry represents or owns the free text.

## Rendering

Free text reuses the existing dynamic `getSignText` texture and `renderCustomText` path used by Exit destination text. It therefore keeps the same font, white text, vertical sizing, background integration, scaling, and left/right alignment.

Unlike Exit rendering, free text does not render `exit_letter`, `exit_letter_blank`, an Exit Character, or another world-space icon. Its usable width starts in the selected slot and extends through consecutive empty slots until another configured sign. The flipped entry extends in the opposite direction.

The palette uses a transparent built-in sign resource plus the localized `自由文字|Free Text` label so the choice is identifiable without adding a world-space character. The in-place Railway Sign preview renders the actual slot text.

Empty free text renders no text or placeholder. It still remains editable until the user clears or replaces the slot.

## Validation And Compatibility

The client and server cap each free-text value at 1024 characters before storage. Packet array lengths must match the Railway Sign length; malformed or mismatched arrays do not partially overwrite existing block-entity state.

Existing Railway Signs retain their sign IDs and selected Exit, platform, line, or station information. Free-text editing does not clear or consume the shared `selectedIds` collection, and free-text rendering never resolves a Station.

## Tests

Focused tests cover:

- old NBT without free-text keys loads empty values;
- each slot persists and reloads its own text;
- slot values do not leak between separate Railway Sign block entities;
- replacing or clearing a free-text slot clears only that slot's text;
- switching normal and flipped free-text modes preserves the slot text;
- packet serialization preserves Unicode, `|`, empty values, and slot order;
- mismatched arrays do not partially mutate block-entity state;
- client and server enforce the 1024-character limit;
- layout includes the selected slot, expands only through adjacent empty slots, and mirrors for flipped text;
- free-text rendering does not enter the Exit Character or Station Exit path;
- existing Exit Information and `selectedIds` remain unchanged.

After focused tests pass, run the complete Fabric test task and production Fabric build together with the approved interchange classification changes.
