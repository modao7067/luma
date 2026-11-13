package io.github.modao7067.luma.shared;

import android.content.Intent;
import android.net.Uri;
import java.util.Arrays;
import java.util.Locale;

final class TerminalSystemLinks {
    static Intent intent(Uri uri){
        String scheme=uri.getScheme()==null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String action=scheme.equals("tel") ? Intent.ACTION_DIAL
                : Arrays.asList("mailto","sms","smsto").contains(scheme) ? Intent.ACTION_SENDTO : Intent.ACTION_VIEW;
        return new Intent(action,uri);
    }
}
