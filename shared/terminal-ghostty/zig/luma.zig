const std = @import("std");
const terminal = @import("terminal.zig");
const modes = @import("../modes.zig");
const Screen = @import("../Screen.zig");
const Style = @import("../style.zig").Style;
const lib = @import("../lib.zig");
const names = .{ "cursor_keys", "reverse_colors", "origin", "wraparound", "cursor_visible", "keypad_keys", "mouse_event_normal", "mouse_event_button", "focus_event", "mouse_format_sgr", "bracketed_paste", "enable_left_and_right_margin", "mouse_event_any", "mouse_format_sgr_pixels" };
const bits = .{ 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 8192, 16384 };
fn flags(values: modes.ModePacked) i32 {
    var result: i32 = 0;
    inline for (names, bits) |name, bit| { if (@field(values, name)) result |= bit; }
    return result;
}
fn color(value: Style.Color, default: i32) i32 {
    return switch (value) {
        .none => default,
        .palette => |index| index,
        .rgb => |rgb| @bitCast(@as(u32, 0xff000000) | (@as(u32, rgb.r) << 16) | (@as(u32, rgb.g) << 8) | rgb.b),
    };
}
fn effects(style: Style) i32 {
    const f = style.flags;
    return @as(i32, if (f.bold) 1 else 0) | @as(i32, if (f.italic) 2 else 0) |
        @as(i32, if (f.underline != .none) 4 else 0) | @as(i32, if (f.blink) 8 else 0) |
        @as(i32, if (f.inverse) 16 else 0) | @as(i32, if (f.invisible) 32 else 0) |
        @as(i32, if (f.strikethrough) 64 else 0) | @as(i32, if (f.faint) 256 else 0);
}
fn charset(out: []i32, state: Screen.CharsetState) void {
    out[0] = @intFromBool(state.charsets.g0 == .dec_special);
    out[1] = @intFromBool(state.charsets.g1 == .dec_special);
    out[2] = @intFromBool(state.gl == .G0);
}
fn saved(out: []i32, screen: ?*Screen) void {
    out[2] = 256; out[3] = 257; out[5] = 24; out[8] = 1;
    if (screen) |s| { if (s.saved_cursor) |cursor| {
        out[0] = cursor.y; out[1] = cursor.x;
        out[2] = color(cursor.style.fg_color, 256); out[3] = color(cursor.style.bg_color, 257);
        out[4] = effects(cursor.style); out[5] = if (cursor.origin) 28 else 24;
        charset(out[6..9], cursor.charset);
    } }
}
pub fn checkpoint(handle: terminal.Terminal, out: [*]i32, len: usize) callconv(lib.calling_conv) c_int {
    if (len < 80) return -1;
    const t = terminal.zigTerminal(handle) orelse return -1;
    @memset(out[0..80], 0); @memset(out[38..70], -1);
    out[0] = 1; out[1] = t.cols; out[2] = t.rows;
    out[3] = flags(t.modes.values); out[4] = flags(t.modes.saved);
    out[5] = @intFromBool(t.screens.active_key == .alternate);
    out[6] = @intFromBool(t.modes.get(.insert));
    out[7] = switch (t.screens.active.cursor.cursor_style) { .block, .block_hollow => 0, .underline => 1, .bar => 2 };
    out[8] = t.scrolling_region.top; out[9] = @as(i32, t.scrolling_region.bottom) + 1;
    out[10] = t.scrolling_region.left; out[11] = @as(i32, t.scrolling_region.right) + 1;
    charset(out[12..15], t.screens.active.charset);
    saved(out[16..25], t.screens.get(.primary)); saved(out[25..34], t.screens.get(.alternate));
    const parser = handle.?.stream.parser;
    out[34] = switch (parser.state) {
        .ground => 0, .escape => 1, .escape_intermediate => 1,
        .csi_entry, .csi_intermediate, .csi_param, .csi_ignore => 6,
        .osc_string => 10, .sos_pm_apc_string => 20,
        .dcs_entry, .dcs_param, .dcs_intermediate, .dcs_passthrough, .dcs_ignore => 13,
    };
    const cursor = t.screens.active.cursor;
    out[71] = effects(cursor.style); out[72] = color(cursor.style.fg_color, 256); out[73] = color(cursor.style.bg_color, 257);
    out[74] = cursor.x; out[75] = cursor.y;
    return 0;
}

const kb_words = 21;

fn writeStack(out: []i32, screen: ?*Screen) void {
    if (screen) |s| {
        out[0] = s.kitty_keyboard.idx;
        for (0..8) |i| out[1 + i] = s.kitty_keyboard.flags[i].int();
    } else {
        out[0] = 0;
        for (0..8) |i| out[1 + i] = 0;
    }
}

pub fn keyboardCheckpoint(handle: terminal.Terminal, out: [*]i32, len: usize) callconv(lib.calling_conv) c_int {
    if (len < kb_words) return -1;
    const t = terminal.zigTerminal(handle) orelse return -1;
    @memset(out[0..kb_words], 0);
    out[0] = 1;
    if (t.flags.modify_other_keys_2) out[1] |= 1;
    if (t.modes.values.alt_esc_prefix) out[1] |= 1 << 1;
    if (t.modes.values.ignore_keypad_with_numlock) out[1] |= 1 << 2;
    if (t.modes.values.backarrow_key_mode) out[1] |= 1 << 3;
    if (t.modes.saved.alt_esc_prefix) out[1] |= 1 << 4;
    if (t.modes.saved.ignore_keypad_with_numlock) out[1] |= 1 << 5;
    if (t.modes.saved.backarrow_key_mode) out[1] |= 1 << 6;
    const primary = t.screens.get(.primary) orelse return -1;
    writeStack(out[2..11], primary);
    if (t.screens.get(.alternate)) |alt| {
        out[1] |= 1 << 7;
        writeStack(out[11..20], alt);
    } else {
        writeStack(out[11..20], null);
    }
    return 0;
}

fn validStack(words: []const i32) bool {
    if (words[0] < 0 or words[0] > 7) return false;
    for (0..8) |i| if (words[1 + i] < 0 or words[1 + i] > 31) return false;
    return true;
}

fn applyStack(screen: *Screen, words: []const i32) void {
    screen.kitty_keyboard.idx = @intCast(words[0]);
    for (0..8) |i| screen.kitty_keyboard.flags[i] = @bitCast(@as(u5, @intCast(words[1 + i])));
}

pub fn keyboardRestore(handle: terminal.Terminal, in: [*]const i32, len: usize) callconv(lib.calling_conv) c_int {
    if (len < kb_words or in[0] != 1) return -1;
    const t = terminal.zigTerminal(handle) orelse return -1;
    const primary = t.screens.get(.primary) orelse return -1;
    const primary_words = in[2..11];
    const alternate_words = in[11..20];
    if (!validStack(primary_words) or !validStack(alternate_words)) return -1;

    var need_alternate = (in[1] & (1 << 7)) != 0;
    for (alternate_words) |word| {
        if (word != 0) need_alternate = true;
    }
    var alternate = t.screens.get(.alternate);
    if (alternate == null and need_alternate) {
        alternate = t.screens.getInit(t.io(), t.gpa(), .alternate, .{
            .cols = t.cols,
            .rows = t.rows,
            .max_scrollback_bytes = 0,
            .kitty_image_storage_limit = primary.kitty_images.total_limit,
            .kitty_image_loading_limits = primary.kitty_images.image_limits,
        }) catch return -1;
    }

    applyStack(primary, primary_words);
    if (alternate) |screen| applyStack(screen, alternate_words);
    t.flags.modify_other_keys_2 = (in[1] & 1) != 0;
    t.modes.values.alt_esc_prefix = (in[1] & (1 << 1)) != 0;
    t.modes.values.ignore_keypad_with_numlock = (in[1] & (1 << 2)) != 0;
    t.modes.values.backarrow_key_mode = (in[1] & (1 << 3)) != 0;
    t.modes.saved.alt_esc_prefix = (in[1] & (1 << 4)) != 0;
    t.modes.saved.ignore_keypad_with_numlock = (in[1] & (1 << 5)) != 0;
    t.modes.saved.backarrow_key_mode = (in[1] & (1 << 6)) != 0;
    return 0;
}

pub fn animationTick(handle: terminal.Terminal, now_ms: u64) callconv(lib.calling_conv) i64 {
    const t = terminal.zigTerminal(handle) orelse return -1;
    return if (t.screens.active.kitty_images.animationTick(t.screens.active.io, now_ms)) |delay|
        @intCast(delay) else -1;
}

pub fn enableInlineImages(handle: terminal.Terminal) callconv(lib.calling_conv) void {
    const wrapper = handle orelse return;
    wrapper.stream.parser.osc_parser.max_allocating_bytes = 8 * 1024 * 1024 + 1024;
}
pub fn addInlineImage(handle: terminal.Terminal, rgba: [*]const u8, width: u32, height: u32) callconv(lib.calling_conv) c_int {
    const t = terminal.zigTerminal(handle) orelse return -1;
    const screen = t.screens.active;
    const count = @as(u64,width) * height;
    if (width == 0 or height == 0 or count > 4 * 1024 * 1024) return -1;
    const pixels = screen.alloc.dupe(u8,rgba[0..@as(usize,@intCast(count))*4]) catch return -1;
    const storage = &screen.kitty_images;
    const id = storage.nextImageId(.implicit);
    storage.addImage(screen.io,screen.alloc,screen,.{
        .id=id,.width=width,.height=height,.format=.rgba,.data=.{.complete=pixels},
    }) catch { screen.alloc.free(pixels); return -1; };
    const graphics = @import("../kitty/graphics.zig");
    var buffer: [96]u8 = undefined;
    const text = std.fmt.bufPrint(&buffer,"a=p,i={d},q=2",.{id}) catch return -1;
    var command = graphics.CommandParser.parseString(screen.alloc,text) catch return -1;
    defer command.deinit(screen.alloc);
    _ = graphics.execute(screen.io,screen.alloc,t,&command);
    return 0;
}

pub fn selectionValid(handle: terminal.Terminal) callconv(lib.calling_conv) c_int {
    const t = terminal.zigTerminal(handle) orelse return 0;
    const screen = t.screens.active;
    const selection = screen.selection orelse return 0;
    const bounds = selection.bounds.tracked;
    if (bounds.start.garbage or bounds.end.garbage) {
        screen.clearSelection();
        return 0;
    }
    return 1;
}

pub fn touchCompression(handle: terminal.Terminal) callconv(lib.calling_conv) void {
    const t = terminal.zigTerminal(handle) orelse return;
    if (t.screens.get(.primary)) |primary| {
        const state = &primary.pages.page_compression;
        state.* = .{ .activity_serial = state.activity_serial +% 1 };
    }
}

fn packRgb(opt: anytype, def: i32) i32 {
    return if (opt) |c| (@as(i32, c.r) << 16) | (@as(i32, c.g) << 8) | c.b else def;
}

pub fn colorScheme(handle: terminal.Terminal) callconv(lib.calling_conv) c_int {
    const t = terminal.zigTerminal(handle) orelse return -1;
    const bg = packRgb(t.colors.background.get(), 0x000000);
    const r: f64 = @floatFromInt((bg >> 16) & 0xff);
    const g: f64 = @floatFromInt((bg >> 8) & 0xff);
    const b: f64 = @floatFromInt(bg & 0xff);
    const brightness = @sqrt(r * r * 0.241 + g * g * 0.691 + b * b * 0.068);
    return if (brightness < 130.0) 1 else 0;
}

pub fn stateFlags(handle: terminal.Terminal, dyn_out: ?*[3]i32) callconv(lib.calling_conv) c_int {
    const wrapper = handle orelse return 0;
    const t = wrapper.terminal;
    const screen = t.screens.active;
    var result: c_int = 0;
    if (t.flags.dirty.palette) result |= 1 << 0;
    if (t.screens.active_key == .alternate) result |= 1 << 1;
    const rev = t.modes.get(.reverse_colors);
    if (rev) result |= 1 << 2;
    if (screen.kitty_images.placements.count() > 0) result |= 1 << 3;
    if (screen.pages.viewport == .active) result |= 1 << 4;
    if (!wrapper.stream.ground()) result |= 1 << 5;
    if (dyn_out) |dyn| {
        const fg = packRgb(t.colors.foreground.get(), 0xffffff);
        const bg = packRgb(t.colors.background.get(), 0x000000);
        const eff_fg = if (rev) bg else fg;
        const eff_bg = if (rev) fg else bg;
        dyn[0] = eff_fg;
        dyn[1] = eff_bg;
        dyn[2] = packRgb(t.colors.cursor.get(), eff_fg);
    }
    return result;
}

pub fn readColors(handle: terminal.Terminal, out: [*]i32, len: usize) callconv(lib.calling_conv) c_int {
    if (len < 259) return -1;
    const t = terminal.zigTerminal(handle) orelse return -1;
    for (t.colors.palette.current, 0..) |c, i| {
        out[i] = (@as(i32, c.r) << 16) | (@as(i32, c.g) << 8) | c.b;
    }
    const fg = packRgb(t.colors.foreground.get(), 0xffffff);
    const bg = packRgb(t.colors.background.get(), 0x000000);
    const rev = t.modes.get(.reverse_colors);
    const eff_fg = if (rev) bg else fg;
    const eff_bg = if (rev) fg else bg;
    out[256] = eff_fg;
    out[257] = eff_bg;
    out[258] = packRgb(t.colors.cursor.get(), eff_fg);
    return 259;
}
