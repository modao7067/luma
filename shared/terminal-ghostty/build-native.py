#!/usr/bin/env python3
COMMAND_DESCRIPTION = """Build the pinned Android ARM64 Ghostty core and Luma JNI; no network or App installation."""
from pathlib import Path
import argparse, os, shutil, subprocess

HERE=Path(__file__).resolve().parent
parser=argparse.ArgumentParser(description=COMMAND_DESCRIPTION)
parser.add_argument('--source',type=Path,required=True,help='Clean Ghostty 0e6afb2600d4 source (with offline Zig dependencies)')
parser.add_argument('--zig',type=Path,required=True,help='Zig 0.16.0 executable')
parser.add_argument('--ndk',type=Path,required=True,help='Android NDK r27c root')
parser.add_argument('--build-dir',type=Path,default=HERE/'build')
parser.add_argument('--local-cache',type=Path)
parser.add_argument('--global-cache',type=Path)
args=parser.parse_args()
ORIGINAL=args.source.resolve(); BUILD=args.build_dir.resolve();SRC=BUILD/'ghostty-source'
zig=args.zig.resolve();ndk=args.ndk.resolve()
version=subprocess.check_output([str(zig),'version'],text=True).strip()
if version != '0.16.0':parser.error('This adapter requires Zig 0.16.0, got '+version)
if not (ORIGINAL/'src/lib_vt.zig').is_file():parser.error('Missing Ghostty source')
BUILD.mkdir(parents=True,exist_ok=True)
if not SRC.exists():shutil.copytree(ORIGINAL,SRC,ignore=shutil.ignore_patterns('.zig-cache','zig-out','.git','result','result-*'))
shutil.copy2(ORIGINAL/'src/terminal/Screen.zig',SRC/'src/terminal/Screen.zig')
subprocess.run(['patch','--batch','-p1','-i',str(HERE/'patches/scrollclear-e257bb4-functional-backport.patch')],cwd=SRC,check=True)
shutil.copy2(HERE/'zig/luma.zig',SRC/'src/terminal/c/luma.zig')
exports={'checkpoint':'luma_ghostty_checkpoint','keyboardCheckpoint':'luma_ghostty_keyboard_checkpoint','keyboardRestore':'luma_ghostty_keyboard_restore','animationTick':'luma_ghostty_animation_tick','enableInlineImages':'luma_ghostty_inline_enable','addInlineImage':'luma_ghostty_inline_add','selectionValid':'luma_ghostty_selection_valid','touchCompression':'luma_ghostty_compression_touch','stateFlags':'luma_ghostty_state_flags','readColors':'luma_ghostty_read_colors','colorScheme':'luma_ghostty_color_scheme'}
f=SRC/'src/terminal/c/main.zig';s=(ORIGINAL/'src/terminal/c/main.zig').read_text()
for name,symbol in exports.items():s+='\npub const '+symbol+' = @import("luma.zig").'+name+';\n'
f.write_text(s)
f=SRC/'src/lib_vt.zig';s=(ORIGINAL/'src/lib_vt.zig').read_text()
for name,symbol in exports.items():
 line='        @export(&c.'+symbol+', .{ .name = "'+symbol+'" });'
 s=s.replace('        const c = terminal.c_api;','        const c = terminal.c_api;\n'+line)
f.write_text(s)
f=SRC/'src/terminal/osc.zig';s=(ORIGINAL/'src/terminal/osc.zig').read_text()
old='            .@"1337",\n            => switch (c) {\n                \';\' => self.captureTrailing(.fixed),'
assert old in s;s=s.replace(old,old.replace('.fixed','.allocating'));f.write_text(s)
f=SRC/'src/terminal/osc/parsers/iterm2.zig';s=(ORIGINAL/'src/terminal/osc/parsers/iterm2.zig').read_text()
s=s.replace('pub fn parse(parser: *Parser, _: ?u8)', 'pub fn parse(parser: *Parser, terminator_ch: ?u8)')
anchor='    cap.writer.writeByte(0) catch {'
inline='    const raw = cap.trailing();\n    if (std.mem.startsWith(u8, raw, "File=") or std.mem.startsWith(u8, raw, "MultipartFile=") or\n        std.mem.startsWith(u8, raw, "FilePart=") or std.mem.eql(u8, raw, "FileEnd")) {\n        const count = raw.len;\n        cap.writeSlice("     ") catch return null;\n        const all = cap.trailing();\n        std.mem.copyBackwards(u8, all[5..], all[0..count]);\n        @memcpy(all[0..5], "1337;");\n        parser.command = .{ .unknown = .{ .content = all, .truncated = false,\n            .terminator = @import("../../osc.zig").Terminator.init(terminator_ch) } };\n        return &parser.command;\n    }\n'
assert anchor in s;s=s.replace(anchor,inline+anchor);f.write_text(s)
f=SRC/'src/terminal/kitty/graphics_image.zig';s=(ORIGINAL/'src/terminal/kitty/graphics_image.zig').read_text()
start=s.index('        var file = std.Io.Dir.cwd().openFile(',s.index('    fn readFile('))
end=s.index('        // We\'ll populate a delete path',start)
s=s[:start]+'''        const fd = luma_open_frame_file(path.ptr, path.len);
        if (fd < 0) return error.InvalidData;
        var file: std.Io.File = .{ .handle = fd, .flags = .{ .nonblocking = false } };

'''+s[end:]
s='extern fn luma_open_frame_file(path: [*]const u8, len: usize) c_int;\n'+s
f.write_text(s)
env=os.environ.copy();env['ANDROID_NDK_HOME']=str(ndk)
for key,value in [('ZIG_LOCAL_CACHE_DIR',args.local_cache),('ZIG_GLOBAL_CACHE_DIR',args.global_cache)]:
 if value:env[key]=str(value.resolve())
subprocess.run([str(zig),'build','-Demit-lib-vt','-Doptimize=ReleaseFast','-Dtarget=aarch64-linux-android','--prefix',str(BUILD/'ghostty')],cwd=SRC,env=env,check=True)
sysroots=list((ndk/'toolchains/llvm/prebuilt').glob('*/sysroot'))
if len(sysroots)!=1:parser.error('Expected one NDK sysroot')
sysroot=sysroots[0];crt=sysroot/'usr/lib/aarch64-linux-android/29'
libc=BUILD/'libc-android-29.txt'
libc.write_text('include_dir='+str(sysroot/'usr/include')+'\nsys_include_dir='+str(sysroot/'usr/include/aarch64-linux-android')+'\ncc_dir='+str(crt)+'\ncrt_dir='+str(crt)+'\nmsvc_lib_dir=\nkernel32_lib_dir=\ngcc_dir=\n')
out=BUILD/'out/libluma_vt_jni.so';out.parent.mkdir(exist_ok=True)
subprocess.run([str(zig),'build-lib','-dynamic','-lc','-ljnigraphics','-O','ReleaseSafe','-target','aarch64-linux-android','-z','max-page-size=16384','--libc',str(libc),'-I',str(BUILD/'ghostty/include'),'-I',str(sysroot/'usr/include'),'-L',str(crt),'-femit-bin='+str(out),str(HERE/'jni/luma_vt_jni.c'),str(BUILD/'ghostty/lib/libghostty-vt.a')],env=env,check=True)
package=HERE/'jniLibs/arm64-v8a/libluma_vt_jni.so';package.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(out,package)
print('Built and staged',package)
