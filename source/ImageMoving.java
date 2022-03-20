import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ClassName FileOperation_Bak
 * @Description
 * @Author StarLee
 * @Date 2022/3/6
 */

public class ImageMoving {
    private static Pattern compile;
    private static ArrayList<File> files;
    private static ArrayList<String> filesName;

    private static class PropertiesInfo {
        static String notesDir;
        static String notesType;
        static String imageBedPath;  // 笔记同目录建立图床
        static String notesBackupPath;
        static String oldImagesBedPathReg;
        static String fullNameReg;
        static char pathChar;
        static boolean AllUTF8;
        static boolean CaseSensitive;
        static boolean KEEP_ORIGIN;

        static void loadProperties() {
            Properties properties = new Properties();
            try (FileInputStream fileInputStream = new FileInputStream("../ImageMoving.properties");
                 BufferedReader bufferedReader = new BufferedReader(
                         new InputStreamReader(fileInputStream, StandardCharsets.UTF_8))) {
                properties.load(bufferedReader);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(-1);
            }
            notesDir = properties.get("NotesDir").toString();
            notesType = properties.get("NotesType").toString();
            oldImagesBedPathReg = properties.get("ImagesBedPathReg").toString();
            String imageNameReg = properties.get("ImageNameReg").toString();
            String encodingUTF8 = properties.get("EncodingUTF8").toString();
            String ignoreCase = properties.get("IgnoreCase").toString();
            String keepOriginImage = properties.get("KeepOriginImages").toString();
            check(imageNameReg, encodingUTF8, ignoreCase, keepOriginImage);
            pathChar = oldImagesBedPathReg.contains("/") ? '/' : '\\';
            AllUTF8 = encodingUTF8.equalsIgnoreCase("yes");
            CaseSensitive = ignoreCase.equalsIgnoreCase("yes");
            KEEP_ORIGIN = keepOriginImage.equalsIgnoreCase("yes");
            fullNameReg = oldImagesBedPathReg + imageNameReg;
            compile = PropertiesInfo.CaseSensitive ? Pattern.compile(PropertiesInfo.fullNameReg)
                    : Pattern.compile(PropertiesInfo.fullNameReg, Pattern.CASE_INSENSITIVE);
            imageBedPath = notesDir + "/vx_images/";
            notesBackupPath = notesDir + "/notes_bak/";
            System.out.println("=================================================================================");
            System.out.println("读取到配置信息:\n" + "NotesDir = " + notesDir + "\nNotesType= " + notesType
                    + "\nImagesBedPathReg = " + oldImagesBedPathReg + "\nImageNameReg = " + imageNameReg
                    + "\n笔记是否全为UTF8编码 = " + encodingUTF8 + "\n是否区分大小写？ " + ignoreCase
                    + "\n是否保留原图床内的图片？ " + keepOriginImage);
            System.out.println("=================================================================================");
        }

        static void check(String imageNameReg, String encodingUTF8, String ignoreCase, String keepOriginImage) {
            checkLength(notesDir);
            if (notesDir.charAt(notesDir.length() - 1) == '/') {
                notesDir = notesDir.substring(0, notesDir.length() - 1);
            }
            checkLength(notesType);
            checkLength(oldImagesBedPathReg);
            checkLength(imageNameReg);
            checkValid(encodingUTF8);
            checkValid(ignoreCase);
            checkValid(keepOriginImage);
        }

        static void checkLength(String s) {
            if (s.length() == 0) {
                try {
                    throw new IllegalArgumentException("配置文件项不可为空");
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
            }
        }

        static void checkValid(String s) {
            checkLength(s);
            if (!s.equalsIgnoreCase("yes") && !s.equalsIgnoreCase("no")) {
                try {
                    throw new IllegalArgumentException("配置文件选择项应填 yes 或者 no");
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
            }
        }
    }

    public static void main(String[] args) {
        PropertiesInfo.loadProperties();
        mkDirs();
        getFilesInfo();
        backupNotes();
        int pageSize = 1 << 13;
        int pageCount = (files.size() + pageSize - 1) / pageSize; // 分页
        // 防止md文件内容过多(>=1.5GB),导致JVM OOM
        for (int i = 0; i < pageCount; i++) {
            int round = i + 1;
            System.out.println("************************************第" + round + "轮处理************************************");
            int begin = i * 1000;
            int end = Math.min(begin + 1000, files.size());
            Map<String, String[]> notesInfo = getFilesData(begin, end);
            Map<String, Map<String, String>> imageNames = collectImageNames(notesInfo);
            if (imageNames != null) {
                moveImages(imageNames, round);
                updateImagePath(notesInfo, imageNames);
            }
            System.out.println("**********************************第" + round + "轮处理完成***********************************");
        }

    }

    private static void mkDirs() {
        Path pathImageBed = Paths.get(PropertiesInfo.imageBedPath);
        if (!Files.exists(pathImageBed)) {  // 创建图床目录
            try {
                Files.createDirectory(pathImageBed);
                System.out.println("新图床创建成功！");
            } catch (IOException e) {
                System.out.println("新图床创建失败！");
                System.exit(-1);
            }
        }
        Path backupPathNotes = Paths.get(PropertiesInfo.notesBackupPath);
        if (!Files.exists(backupPathNotes)) {  // 创建图床目录
            try {
                Files.createDirectory(backupPathNotes);
                System.out.println("备份目录创建成功！");
            } catch (IOException e) {
                System.out.println("备份目录创建失败,或许可以用管理员身份运行bat解决~");
                System.exit(-1);
            }
        }
    }

    private static void getFilesInfo() {
        File[] files = new File(PropertiesInfo.notesDir).listFiles();
        assert files != null;
        ArrayList<File> filter = new ArrayList<>(files.length);
        ArrayList<String> filesName = new ArrayList<>(files.length);
        for (File file : files) {
            if (Files.isDirectory(file.toPath())) continue;
            String fileName = file.getName();
            int pointIndex = fileName.length();
            while (pointIndex > 0 && fileName.charAt(--pointIndex) != '.') ;
            if (fileName.substring(pointIndex + 1).equalsIgnoreCase(PropertiesInfo.notesType)) {
                filter.add(file);
                filesName.add(fileName);
            }
        }
        ImageMoving.files = filter;
        ImageMoving.filesName = filesName;
    }

    private static void backupNotes() {
        File[] newFiles = new File[files.size()];
        for (int i = 0; i < newFiles.length; i++) {
            newFiles[i] = new File(PropertiesInfo.notesBackupPath + filesName.get(i));
        }
        boolean exception = false;  //正常运行标记
        for (int i = 0; i < newFiles.length; i++) {
            if (Files.exists(newFiles[i].toPath()) && newFiles[i].length() != 0) continue;
            try {
                moveResource(files.get(i), newFiles[i]);
            } catch (IOException e) {
                System.out.println(filesName.get(i) + "备份失败");
                exception = true;
            }
        }
        if (exception) System.exit(-1);
        System.out.println("===================================备份成功========================================");
    }

    /**
     * @MethodName getFilesData
     * @Description
     */
    private static Map<String, String[]> getFilesData(int begin, int end) {
        Map<String, String[]> notesInfo = new HashMap<>();
        for (int i = begin; i < end; i++) {
            //读取指定文件路径下的文件内容
            String[] fileData = readFile(files.get(i));
            //把文件名作为key,文件内容为value 存储在map中
            if (fileData != null) {
                notesInfo.put(filesName.get(i), fileData);
            }
        }
        return notesInfo;
    }

    /**
     * @return String 将读取到的内容返回
     * @MethodName readFile
     * @Description 读取单个文件的内容
     * @Param [path]
     */
    private static String[] readFile(File path) {
        byte[] content = null;
        try (FileChannel inputChannel = new FileInputStream(path).getChannel()) {
            if (inputChannel.size() >= Integer.MAX_VALUE) {
                System.out.println("Notice: " + path.getName() + "过大,请自行解决！");
                return null;
            }
            MappedByteBuffer mappedByteBuffer = inputChannel.map(FileChannel.MapMode.READ_ONLY,
                    0, inputChannel.size());
            byte[] bytes = new byte[(int) inputChannel.size()];
            mappedByteBuffer.get(bytes);
            content = bytes;
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        if (!PropertiesInfo.AllUTF8 && isGBK(content)) {
            try {
                return new String[]{new String(content, "gbk"), "gbk"};
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        return new String[]{new String(content, StandardCharsets.UTF_8), "utf8"};
    }

    /**
     * from Website
     */
    private static boolean isGBK(byte[] buffer) {
        boolean isGbk = true;
        int end = buffer.length;
        for (int i = 0; i < end; i++) {
            byte temp = buffer[i];
            if ((temp & 0x80) == 0) {
                continue;// B0A1-F7FE//A1A1-A9FE
            } else if ((Byte.toUnsignedInt(temp) < 0xAA && Byte.toUnsignedInt(temp) > 0xA0)
                    || (Byte.toUnsignedInt(temp) < 0xF8 && Byte.toUnsignedInt(temp) > 0xAF)) {
                if (i + 1 < end) {
                    if (Byte.toUnsignedInt(buffer[i + 1]) < 0xFF && Byte.toUnsignedInt(buffer[i + 1]) > 0xA0
                            && Byte.toUnsignedInt(buffer[i + 1]) != 0x7F) {
                        i = i + 1;
                        continue;
                    }
                } // 8140-A0FE
            } else if (Byte.toUnsignedInt(temp) < 0xA1 && Byte.toUnsignedInt(temp) > 0x80) {
                if (i + 1 < end) {
                    if (Byte.toUnsignedInt(buffer[i + 1]) < 0xFF && Byte.toUnsignedInt(buffer[i + 1]) > 0x3F
                            && Byte.toUnsignedInt(buffer[i + 1]) != 0x7F) {
                        i = i + 1;
                        continue;
                    }
                } // AA40-FEA0//A840-A9A0
            } else if ((Byte.toUnsignedInt(temp) < 0xFF && Byte.toUnsignedInt(temp) > 0xA9)
                    || (Byte.toUnsignedInt(temp) < 0xAA && Byte.toUnsignedInt(temp) > 0xA7)) {
                if (i + 1 < end) {
                    if (Byte.toUnsignedInt(buffer[i + 1]) < 0xA1 && Byte.toUnsignedInt(buffer[i + 1]) > 0x3F
                            && Byte.toUnsignedInt(buffer[i + 1]) != 0x7F) {
                        i = i + 1;
                        continue;
                    }
                }
            }
            isGbk = false;
            break;
        }
        return isGbk;
    }

//    private static boolean isUTF8(byte[] buffer){
//        boolean isUtf8 = true;
//        int end = buffer.length;
//        for (int i = 0; i < end; i++) {
//            byte temp = buffer[i];
//            if ((temp & 0x80) == 0) {// 0xxxxxxx
//                continue;   // 只有一个字节
//            } else if ((temp & 0x40) == 0x40 && (temp & 0x20) == 0) {// 110xxxxx 10xxxxxx
//                // 有两个字节
//                if (i + 1 < end && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0) {
//                    continue;
//                }
//            } else if ((temp & 0xE0) == 0xE0 && (temp & 0x10) == 0) {// 1110xxxx 10xxxxxx 10xxxxxx
//                // 有三个字节
//                if (i + 2 < end && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0
//                        && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0) {
//                    continue;
//                }
//            } else if ((temp & 0xF0) == 0xF0 && (temp & 0x08) == 0) {// 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
//                // 有四个字节
//                if (i + 3 < end && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0
//                        && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0
//                        && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0) {
//                    continue;
//                }
//            }
//            isUtf8 = false;
//            break;
//        }
//        return isUtf8;
//    }

    /**
     * @return Map<String, ArrayList < String [ ]>>
     * 将每张图片以[笔记名,图片全路径全名|图片名]形式保存到Map中
     * @MethodName collectImageNames
     * @Description 收集当前目录下所有笔记内的图片引用路径
     * @Param [notesInfo]
     */
    private static Map<String, Map<String, String>> collectImageNames(Map<String, String[]> notesInfo) {
        Map<String, Map<String, String>> imageNames = new HashMap<>();
        // 初始化Map
        for (String fileName : notesInfo.keySet()) {
            imageNames.put(fileName, new HashMap<>());
        }
        int imageNum = 0;
        for (Map.Entry<String, String[]> entry : notesInfo.entrySet()) {
            Matcher matcher = compile.matcher(entry.getValue()[0]);
            while (matcher.find()) {
                String imageFullName = matcher.group(0);
                int begin = imageFullName.length() - 4;
                while (begin > 0 && (imageFullName.charAt(--begin) != PropertiesInfo.pathChar)) ;
                String imageName = imageFullName.substring(++begin);
                imageNames.get(entry.getKey()).put(imageFullName, imageName);
                imageNum++;
            }
        }
        if (imageNum == 0) {
            System.out.println("本轮未匹配到图片名。 原因: 1. 本轮笔记中不含有图片引用  " +
                    "2. 配置的(路径信息|图片名正则表达式)不正确");
            return null;
        } else {
            System.out.println("本轮已匹配到" + imageNum + "个图片,正在处理中！");
        }
        return imageNames;
    }

    private static void moveImages(Map<String, Map<String, String>> imageNames, int round) {
        int failNum = 0;
        int imageNum = 0;
        for (Map<String, String> value : imageNames.values()) {
            imageNum += value.size();
        }
        for (Map.Entry<String, Map<String, String>> entry : imageNames.entrySet()) {
            Map<String, String> imageNameInfo = entry.getValue();
            ArrayList<String> failImages = new ArrayList<>(imageNameInfo.size());
            System.out.println(entry.getKey() + " 中需要处理的图片有：" + imageNameInfo.size() + "张");
            for (Map.Entry<String, String> imageNameEntry : imageNameInfo.entrySet()) {
                String imageFullName = imageNameEntry.getKey();
                String imageName = imageNameEntry.getValue();
                File origin = new File(imageFullName);
                File target = new File(PropertiesInfo.imageBedPath + imageName);
                if (!Files.exists(origin.toPath())) {
                    System.out.println(imageFullName + "  |  移动失败！！ |  失败原因：图片不存在！");
                    failImages.add(imageFullName);
                } else if (Files.exists(target.toPath()) && target.length() != 0) {
                    System.out.println(imageFullName + "  已存在于新图床下,主动放弃迁移~~~");
                } else {
                    try {
                        moveResource(origin, target);
                    } catch (IOException e) {
                        System.out.println(imageFullName + "  |  移动失败！！");
                        failImages.add(imageFullName);
                    }
                }
            }
            failNum += failImages.size();
            for (String failImage : failImages) {
                imageNameInfo.keySet().remove(failImage);
            }
            System.out.println("=================================================================================");
        }
        // 判断是移动图片，还是拷贝图片
        if (!PropertiesInfo.KEEP_ORIGIN) {
            System.gc();
            for (Map<String, String> names : imageNames.values()) {
                for (String imageFullName : names.keySet()) {
                    try {
                        Files.delete(Paths.get(imageFullName));
                    } catch (IOException e) {
                        System.out.println(imageFullName + "移动成功！但源文件删除失败!");
                    }
                }
            }
        }
        System.out.println("第" + round + "轮最终处理失败的图片为" + failNum + "张！");
        if (imageNum == failNum) {
            System.exit(-1);
        }
    }

    private static void moveResource(File origin, File target) throws IOException {
        try (FileChannel inputChannel = (FileChannel) Channels.newChannel(new FileInputStream(origin));
             FileChannel outputChannel = (FileChannel) Channels.newChannel(new FileOutputStream(target))) {
            inputChannel.transferTo(0, inputChannel.size(), outputChannel);
        }
        if (target.length() == 0) {
            System.out.println(target + "  在移动过程中出现异常,将显示为空白文件。以防意外,保留了原文件请手动移动。。。。");
            throw new IOException();
        }
    }

    private static void updateImagePath(Map<String, String[]> notesInfo, Map<String, Map<String, String>> imageNames) {
        ArrayList<String> succeedNotes = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : notesInfo.entrySet()) {
            String noteName = entry.getKey();
            Map<String, String> hashMap = imageNames.get(noteName);
            if (hashMap.isEmpty()) continue;
            String content = replace(entry.getValue()[0], hashMap);
            byte[] bytes;
            if (entry.getValue()[1].equals("gbk")) {
                try {
                    bytes = content.getBytes("gbk");
                } catch (UnsupportedEncodingException e) {
                    System.out.println("Notice:  " + noteName + "  更新图片路径失败！请手动更改！ 原因：不支持的文件编码");
                    continue;
                }
            } else {
                bytes = content.getBytes(StandardCharsets.UTF_8);
            }
            try (FileChannel rwChannel = new RandomAccessFile(PropertiesInfo.notesDir + "\\"
                    + noteName, "rw").getChannel()) {
                MappedByteBuffer mappedByteBuffer = rwChannel.map(FileChannel.MapMode.READ_WRITE,
                        0, bytes.length);
                mappedByteBuffer.put(bytes);
                succeedNotes.add(noteName);
            } catch (IOException e) {
                System.out.println("Notice:  " + noteName + "  更新图片路径失败！请手动更改！ 原因：新路径无法写回文件");
            }
        }
        System.out.println("=================================================================================");
        System.out.println("运行结束！成功写回(更新)的文件有：");
        for (String succeedNote : succeedNotes) {
            System.out.println(succeedNote);
        }
    }

    private static String replace(String rawStr, Map<String, String> succeedImages) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = compile.matcher(rawStr);
        while (matcher.find()) {
            String s = matcher.group(0);
            if (succeedImages.containsKey(s)) {
                matcher.appendReplacement(sb, "vx_images/" + succeedImages.get(s));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
