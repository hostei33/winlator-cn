package com.winlator.box64;

import android.content.Context;

import com.winlator.core.ArrayUtils;
import com.winlator.core.StreamUtils;
import com.winlator.xenvironment.RootFS;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public abstract class Box64Utils {
    public static String extractBinVersion(Context context) {
        File binFile = new File(RootFS.find(context).getRootDir(), "/usr/local/bin/box64");
        try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(binFile), StreamUtils.BUFFER_SIZE)) {
            final byte[] str = {'B','o','x','6','4',' ','a','r','m','6','4',' ','v'};
            byte[] buffer = new byte[StreamUtils.BUFFER_SIZE + str.length - 1];
            int bytesInBuffer = 0;
            while (true) {
                // 尽量填满缓冲区，避免 read 未读满提前结束
                while (bytesInBuffer < buffer.length) {
                    int r = inStream.read(buffer, bytesInBuffer, buffer.length - bytesInBuffer);
                    if (r == -1) break;
                    bytesInBuffer += r;
                }
                if (bytesInBuffer == 0) return "";

                int index = ArrayUtils.indexOf(buffer, 0, bytesInBuffer, str);
                if (index != ArrayUtils.INDEX_NOT_FOUND) {
                    int start = index + str.length;
                    int end = ArrayUtils.indexOf(buffer, start, bytesInBuffer, (byte)' ');
                    return end != ArrayUtils.INDEX_NOT_FOUND ? new String(buffer, start, end - start) : "";
                }

                // 已读完整个文件仍未匹配
                if (bytesInBuffer < buffer.length) return "";

                // 保留末尾 str.length-1 字节与下一块重叠，防止版本字符串跨块匹配失败
                System.arraycopy(buffer, bytesInBuffer - (str.length - 1), buffer, 0, str.length - 1);
                bytesInBuffer = str.length - 1;
            }
        }
        catch (IOException e) {}
        return "";
    }
}
