test "Luma scrollClear pruned styled hyperlink cursor" {
    const testing = std.testing;
    var s = try init(testing.io, testing.allocator, .{
        .cols = 10,
        .rows = 10,
        .max_scrollback_bytes = 1,
        .max_scrollback_lines = 1,
    });
    defer s.deinit();
    const first = s.pages.pages.first.?;
    const capacity = first.capacity().rows;
    first.page().pauseIntegrityChecks(true);
    for (0..capacity - 5) |_| try s.testWriteString("\n");
    first.page().pauseIntegrityChecks(false);
    try s.testWriteString("1\n2\n3\n4\n5\n6\n7\n8\n9\n10");
    try testing.expect(s.pages.pages.first != s.pages.pages.last);
    s.cursorAbsolute(0, 0);
    try s.setAttribute(.{ .bold = {} });
    try s.startHyperlink("https://example.com/prune", null);
    const old = s.cursor.page_pin.node;
    try testing.expect(old == s.pages.pages.first.?);
    try s.scrollClear();
    try testing.expect(s.pages.pages.first.? != old);
    try testing.expect(s.cursor.page_pin.node == s.pages.pages.first.?);
    try testing.expect(!s.cursor.page_pin.garbage);
    const page = s.cursor.page_pin.node.page();
    try testing.expect(s.cursor.style_id != style.default_id);
    try testing.expect(s.cursor.hyperlink_id != 0);
    try testing.expect(page.styles.refCount(page.memory, s.cursor.style_id) > 0);
    try testing.expect(page.hyperlink_set.refCount(page.memory, s.cursor.hyperlink_id) > 0);
    try s.testWriteString("after prune");
    s.assertIntegrity();
}
