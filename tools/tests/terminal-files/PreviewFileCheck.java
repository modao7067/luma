package io.github.modao7067.luma.root;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Arrays;

public final class PreviewFileCheck {
    static int checks;
    static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);checks++;System.out.println("PASS "+s);}
    static byte[] digest(File file)throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[]b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return md.digest();}
    interface Attempt{void run()throws Exception;}
    static void rejects(Attempt fn,String label)throws Exception{boolean rejected=false;try{fn.run();}catch(IOException e){rejected=true;}check(rejected,label);}
    public static void main(String[]args)throws Exception {
        File work=new File(args[1]);
        String guestRepo=args.length>2?args[2]:"/workspace/repo";
        String guestWork=args.length>3?args[3]:(guestRepo+"/tools/tests/terminal-files/build");
        String relPng=args.length>4?args[4]:"tools/tests/terminal-files/build/input/sample.png";
        System.setOut(new PrintStream(new File(work,"preview-file-check.txt")));
        try{
            File source=new File(work,"input/quote ' $(printf WRONG) space.txt"),copy=new File(work,"copied.txt");
            TerminalLinkReader.copy(null,guestWork+"/input/quote ' $(printf WRONG) space.txt",copy,1024);
            check(Arrays.equals(digest(source),digest(copy)),"Ubuntu path + quotes/spaces/dollar signs roundtrip without shell expansion");
            check(TerminalDocuments.text(copy).equals("中文文档\nsecond line\n"),"UTF-8 text preview");
            rejects(()->TerminalLinkReader.copy(null,guestWork+"/input/quote ' $(printf WRONG) space.txt",copy,1),"oversized guest file rejected before reading");
            rejects(()->TerminalLinkReader.copy(null,guestWork+"/input",copy,1024),"guest directory rejected");
            rejects(()->TerminalLinkReader.copy(null,guestWork+"/input/missing",copy,1024),"missing guest path rejected");
            rejects(()->TerminalLinkReader.copy(null,guestWork+"/input/pipe",copy,1024),"guest FIFO rejected without blocking");
            File png=new File(work,"copy.png");TerminalLinkReader.copy(null,guestWork+"/input/sample.png",png,64L*1024*1024);
            Bitmap image=WallpaperImages.decode(png,2048,2048);check(image!=null&&image.getWidth()*image.getHeight()<=WallpaperImages.MAX_PIXELS,"existing bounded image decoder works for popup source");image.recycle();
            File relative=new File(work,"relative.png");TerminalLinkReader.copy(null,relPng,0,relative,64L*1024*1024,false);
            check(Arrays.equals(digest(png),digest(relative)),"relative CLI path resolves against guest workspace, not filesystem root");
            File highlighted=new File(work,"highlighted.txt");TerminalLinkReader.copy(null,guestRepo+"/shared/terminal-view/java/com/termux/view/TerminalRenderer.java",0,highlighted,9L*1024*1024,true);
            String ansi=new String(Files.readAllBytes(highlighted.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            check(ansi.contains("\033[")&&ansi.contains("TerminalRenderer"),"installed Ubuntu bat produces real ANSI code highlighting");
            File markdown=new File(work,"markdown.ansi");TerminalLinkReader.copy(null,guestWork+"/input/sample.md",0,markdown,9L*1024*1024,true,38);
            String renderedMd=new String(Files.readAllBytes(markdown.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            String mdPlain=renderedMd.replaceAll("\\x1b\\[[0-?]*[ -/]*[@-~]","");
            check(mdPlain.contains("排版标题")&&mdPlain.contains("子标题")&&!mdPlain.contains("# ")&&!mdPlain.contains("```"),"Markdown headings/fences are rendered instead of colored raw source");
            check(mdPlain.contains("• 粗体条目")&&!mdPlain.contains("**")&&mdPlain.contains("│ 引用段落"),"Markdown list, emphasis and quote layout");
            check(mdPlain.contains("preview(x)")&&(renderedMd.contains("38;2;")||renderedMd.contains("38;5;"))&&renderedMd.contains("https://example.test"),"Markdown code block has real syntax colors and link target is visible");
            check(mdPlain.contains("表头")&&mdPlain.contains("─"),"Markdown table rendered as terminal cells");
            check(!renderedMd.matches("(?s).*\\x1b\\[(?:3[0-7]|9[0-7])(?:;[0-9]+)*m.*")&&renderedMd.contains("38;2;"),"soft Markdown colors survive non-TTY quantization without neon ANSI 16 promotion");
            String[] fallbackCommand={RootEntry.suExecutable(),"-c","exec "+RootEntry.quote(RootEntry.launcher(null))+" /usr/bin/env PATH=/nonexistent /usr/bin/python3 -I -c "+TerminalFileTransfer.quote(TerminalFileTransfer.SCRIPT)+" "+RootEntry.quote(guestWork+"/input/sample.md")+" 9000000 0 ansi 38"};
            File fallback=new File(work,"markdown-fallback.ansi");TerminalFileTransfer.copy(new ProcessBuilder(fallbackCommand),fallback,9L*1024*1024);
            String basic=new String(Files.readAllBytes(fallback.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            check(basic.contains("排版标题")&&basic.contains("• ")&&!basic.contains("# ")&&!basic.contains("```")&&!basic.contains("**"),"guest without glow still renders basic Markdown structure");
            String failure="";try{TerminalLinkReader.copy(null,"not-present-file.png",0,relative,1024,false);}catch(IOException error){failure=error.getMessage();}
            check(failure.contains(com.termux.terminal.LumaLang.tr("找不到文件","File not found"))&&failure.contains("not-present-file.png"),"actual failing path returned instead of generic permission guess");
            File longText=new File(work,"long.txt");try(OutputStream out=new FileOutputStream(longText)){byte[]b=new byte[TerminalDocuments.MAX_TEXT_BYTES+32];Arrays.fill(b,(byte)'a');out.write(b);}
            String text=TerminalDocuments.text(longText);check(text.startsWith("aaaa")&&text.contains(com.termux.terminal.LumaLang.tr("仅预览前 1 MiB","showing first 1 MiB"))&&text.length()<TerminalDocuments.MAX_TEXT_BYTES+100,"large text preview is bounded and labeled");
            File binary=new File(work,"binary.txt");Files.write(binary.toPath(),new byte[]{'a',0,'b'});rejects(()->TerminalDocuments.text(binary),"binary content not shown as text");
            check(TerminalDocuments.isText("README.MD")&&TerminalDocuments.isText("trace.jsonl")&&!TerminalDocuments.isText("file.apk"),"document type routing");
            File pdf=new File(work,"input/sample.pdf");TerminalDocuments.PdfPage first=TerminalDocuments.pdf(pdf,0,1080,1600),second=TerminalDocuments.pdf(pdf,1,1080,1600);
            check(first.count==2&&second.count==2,"real PdfRenderer reads both pages");
            check(first.bitmap.getWidth()*first.bitmap.getHeight()<=4*1024*1024,"PDF page bitmap budget");
            check(!first.bitmap.sameAs(second.bitmap),"PDF next page actually changes pixels");first.bitmap.recycle();second.bitmap.recycle();
            rejects(()->TerminalDocuments.pdf(pdf,2,1080,1600),"out-of-range PDF page rejected");
            File cache=new File(work,"provider-cache");File folder=new File(cache,"terminal-open");folder.mkdirs();File staged=new File(folder,"open-fixture.data");Files.write(staged.toPath(),new byte[]{1,2,3});
            TerminalFileProvider provider=new TerminalFileProvider();Field context=ContentProvider.class.getDeclaredField("mContext");context.setAccessible(true);
            context.set(provider,new ContextWrapper(null){@Override public File getCacheDir(){return cache;}});
            Uri uri=Uri.parse("content://test.terminal-files/open-fixture.data/app.apk");
            check(provider.getType(uri).equals("application/vnd.android.package-archive"),"APK installer MIME type");
            for(String[] entry:new String[][]{{"https://example.test",Intent.ACTION_VIEW},{"http://example.test",Intent.ACTION_VIEW},{"mailto:test@example.test",Intent.ACTION_SENDTO},{"tel:12345",Intent.ACTION_DIAL},{"smsto:12345",Intent.ACTION_SENDTO},{"geo:1,2",Intent.ACTION_VIEW},{"tg://resolve?domain=test",Intent.ACTION_VIEW}}){
                Intent route=TerminalSystemLinks.intent(Uri.parse(entry[0]));check(route.getAction().equals(entry[1])&&route.getDataString().equals(entry[0])&&route.getComponent()==null&&route.getSelector()==null,"system link delegates safe UI navigation: "+entry[0]);
            }
            try(ParcelFileDescriptor fd=provider.openFile(uri,"r")){check(fd.getStatSize()==3,"provider opens only the staged artifact");}
            rejects(()->provider.openFile(uri,"rw"),"provider denies write access");
            rejects(()->provider.openFile(Uri.parse("content://test.terminal-files/..%2Fsecret/app.apk"),"r"),"provider denies encoded path traversal");
            rejects(()->provider.openFile(Uri.parse("content://test.terminal-files/open-missing.data/app.apk"),"r"),"expired staged file denied");
            try(android.database.Cursor c=provider.query(uri,null,null,null,null)){check(c.moveToFirst()&&c.getString(0).equals("app.apk")&&c.getLong(1)==3,"provider reports original display name and length");}
            System.out.println("ALL PASS "+checks+" native preview/transport/PDF/provider checks; no install/Activity/service");
        }catch(Throwable t){t.printStackTrace(System.out);System.out.flush();System.exit(1);}
    }
}
