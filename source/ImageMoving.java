import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ClassName ImageMoving
 * @Description
 * @Author StarLee
 * @Date 2022/3/6
 */

public class ImageMoving {
    private static Pattern compile;
    private static ArrayList<File> files;
    private static ArrayList<String> filesName;
    /**
     *  配置信息类
     */
    private static class PropertiesInfo {
        static String notesDir;
        static String notesType;
        static String imageBedPath;  // 笔记同目录建立图床
        static String notesBackupPath;
        static String oldImagesBedPathReg;
        static String fullNameReg;
        static char pathChar;
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
            String keepOriginImage = properties.get("KeepOriginImages").toString();
            check(imageNameReg,keepOriginImage);
            pathChar = oldImagesBedPathReg.contains("/") ? '/' : '\\';
            KEEP_ORIGIN = keepOriginImage.equalsIgnoreCase("yes");
            fullNameReg = oldImagesBedPathReg + imageNameReg;
            compile = Pattern.compile(PropertiesInfo.fullNameReg, Pattern.CASE_INSENSITIVE);
            imageBedPath = notesDir + "vx_images/";
            notesBackupPath = notesDir + "notes_bak/";
            System.out.println("=================================================================================");
            System.out.println("读取到配置信息:\n" + "NotesDir = " + notesDir + "\nNotesType= " + notesType
                    + "\nImagesBedPathReg = " + oldImagesBedPathReg + "\nImageNameReg = " + imageNameReg
                    + "\n是否保留原图床内的图片？ " + keepOriginImage);
            System.out.println("=================================================================================");
        }

        static void check(String imageNameReg, String keepOriginImage) {
            checkLength(notesDir);
            if (notesDir.contains("\\")){
                throw new IllegalArgumentException("配置文件中的notesDir路径符应该为/,而不是\\");
            }
            if (notesDir.charAt(notesDir.length() - 1) != '/') {
                notesDir = notesDir + '/';
            }
            checkLength(notesType);
            checkLength(oldImagesBedPathReg);
            checkLength(imageNameReg);
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
        for (int i = 0; i < pageCount;) {
            if (i != 0) System.gc();
            int begin = i * 1000;
            int end = Math.min(begin + 1000, files.size());
            int round = ++i;
            System.out.println("************************************第" + round + "轮处理************************************");
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
                System.out.println("新图床创建失败！ 失败原因：" + e.getMessage());
                System.exit(-1);
            }
        }
        Path backupPathNotes = Paths.get(PropertiesInfo.notesBackupPath);
        if (!Files.exists(backupPathNotes)) {  // 创建图床目录
            try {
                Files.createDirectory(backupPathNotes);
                System.out.println("备份目录创建成功！");
            } catch (IOException e) {
                System.out.println("备份目录创建失败！ 失败原因：" + e.getMessage());
                System.exit(-1);
            }
        }
    }
    /**
     *  提取需要处理的笔记文件
     */
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
            // notesType规定的文件类型才可以加入集合
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
                System.out.println(filesName.get(i) + "备份失败！ 失败原因：" + e.getMessage());
                exception = true;
            }
        }
        if (exception) System.exit(-1);
        System.out.println("===================================备份成功========================================");
    }

    /**
     *  @MethodName getFilesData
     *  @Description  读取笔记内容
     *  @return Map<笔记名,{笔记内容,编码类型}>
     */
    private static Map<String, String[]> getFilesData(int begin, int end) {
        Map<String, String[]> notesInfo = new HashMap<>();
        for (int i = begin; i < end; i++) {
            //读取指定文件路径下的文件内容
            String[] fileData = readFile(files.get(i));
            // 内容大小合适,且内容中含有图片路径的加入Map
            if (fileData != null) {
                Matcher matcher = compile.matcher(fileData[0]);
                if (matcher.find()){
                    notesInfo.put(filesName.get(i), fileData);
                }
            }
        }
        return notesInfo;
    }

    /**
     *  @MethodName readFile
     *  @Description  读取单个笔记
     *  @return {笔记内容,编码类型}
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
        // UTF-8可以fast-fail,既判断类型,也兼容GBK
        if (isGBK(content)) {
            try {
                return new String[]{new String(content, "gbk"), "gbk"};
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                System.exit(-1);
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


    /**
     *  @MethodName collectImageNames
     *  @Description  收集所有笔记内容中的图片全路径名
     *  @return Map<笔记名, Map<图片全路径名,图片名>>
     */
    private static Map<String, Map<String, String>> collectImageNames(Map<String, String[]> notesInfo) {
        if (notesInfo.size() == 0) {
            System.out.println("本轮未匹配到图片名。 原因: 1. 本轮笔记中不含有图片引用  " +
                    "2. 配置的(路径信息|图片名正则表达式)不正确");
            return null;
        }
        // 内容中,存在图片全路径名
        Map<String, Map<String, String>> imageNames = new HashMap<>();
        // 初始化Map
        for (String fileName : notesInfo.keySet()) {
            imageNames.put(fileName, new HashMap<>());
        }
        int imageNum = 0;
        for (Map.Entry<String, String[]> entry : notesInfo.entrySet()) {
            Matcher matcher = compile.matcher(entry.getValue()[0]);
            Map<String, String> curNoteImageInfo = imageNames.get(entry.getKey());
            while (matcher.find()) {
                String imageFullName = matcher.group(0);
                int begin = imageFullName.length() - 4;
                while (begin > 0 && (imageFullName.charAt(--begin) != PropertiesInfo.pathChar)) ;
                String imageName = imageFullName.substring(++begin);
                curNoteImageInfo.put(imageFullName, imageName);
            }
            imageNum += curNoteImageInfo.size();
        }
        System.out.println("本轮已匹配到" + imageNum + "个图片,正在处理中！");
        return imageNames;
    }

    /**
     *  @MethodName moveImages
     *  @Description  迁移图床
     *  @Param [Map<笔记名, Map<图片全路径名, 图片名>>, round]
     */
    private static void moveImages(Map<String, Map<String, String>> imageNames, int round) {
        int failNum = 0;
        for (Map.Entry<String, Map<String, String>> entry : imageNames.entrySet()) {
            Map<String, String> imageNameInfo = entry.getValue();
            // 存放移动失败的图片,以便后续处理
            ArrayList<String> failImages = new ArrayList<>(imageNameInfo.size());
            System.out.println(entry.getKey() + " 中需要处理的图片有：" + imageNameInfo.size() + "张");
            // 移动操作
            for (Map.Entry<String, String> imageNameEntry : imageNameInfo.entrySet()) {
                String imageFullName = imageNameEntry.getKey();
                String imageName = imageNameEntry.getValue();
                File origin = new File(imageFullName);
                File target = new File(PropertiesInfo.imageBedPath + imageName);
                if (!Files.exists(origin.toPath())) {  // 源图片不存在
                    System.out.println(imageFullName + "  |  移动失败！！ |  失败原因：图片不存在！");
                    failImages.add(imageFullName);
                } else if (Files.exists(target.toPath()) && target.length() != 0) {  // 新图片已存在
                    System.out.println(imageFullName + "  已存在于新图床下,主动放弃迁移~~~");
                } else {
                    try {
                        moveResource(origin, target);
                    } catch (IOException e) {
                        System.out.println(imageFullName + "  |  移动失败！！ |  失败原因：" + e.getMessage());
                        failImages.add(imageFullName);
                    }
                }
            }
            // 将移动失败的图片从图片集合中移除
            failNum += failImages.size();
            for (String failImage : failImages) {
                imageNameInfo.remove(failImage);
            }
            System.out.println("=================================================================================");
        }
        boolean beEmpty = true;
        // 清除失败操作后残余的空Map
        for (Map.Entry<String, Map<String, String>> entry : imageNames.entrySet()) {
            if (entry.getValue().isEmpty()){
                imageNames.remove(entry.getKey());
                continue;
            }
            beEmpty = false;
        }
        if (beEmpty){
            System.out.println("Notice：无移动成功的图片,后续操作再无意义！");
            System.exit(-1);
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
    }

    /**
     *  @MethodName moveResource
     *  @Description  移动文件方法(通用)
     *  @Param [origin, target]
     */
    private static void moveResource(File origin, File target) throws IOException {
        try (FileChannel inputChannel = (FileChannel) Channels.newChannel(new FileInputStream(origin));
             FileChannel outputChannel = (FileChannel) Channels.newChannel(new FileOutputStream(target))) {
            inputChannel.transferTo(0, inputChannel.size(), outputChannel);
        }
        // 对移动失败,导致目标创建成空白文件的处理
        if (target.length() == 0) {
            System.out.println(target + "  在移动过程中出现异常,将显示为空白文件。以防意外,保留了原文件请手动移动。。。。");
            throw new IOException();
        }
    }

    /**
     *  @MethodName updateImagePath
     *  @Description  将笔记内容中的旧路径——>新路径
     *  @Param [notesInfo, imageNames]
     */
    private static void updateImagePath(Map<String, String[]> notesInfo, Map<String, Map<String, String>> imageNames) {
        ArrayList<String> failNotes = new ArrayList<>();
        System.gc();
        for (Map.Entry<String, String[]> entry : notesInfo.entrySet()) {
            String noteName = entry.getKey();
            String[] noteInfo = entry.getValue();
            Map<String, String> curNoteImagesInfo = imageNames.get(noteName);
            String content = replaceAll(noteInfo[0], curNoteImagesInfo);
            byte[] bytes;
            if (noteInfo[1].equals("gbk")) {
                try {
                    bytes = content.getBytes("gbk");
                } catch (UnsupportedEncodingException e) {
                    System.out.println("Notice:  " + noteName + "  更新图片路径失败！请手动更改！ 原因：不支持的文件编码");
                    continue;
                }
            } else {
                bytes = content.getBytes(StandardCharsets.UTF_8);
            }

            File file = new File(PropertiesInfo.notesDir + noteName);
            Path path = file.toPath();
            try {
                Files.delete(path);
                Files.createFile(path);
            } catch (IOException e) {
                System.out.println(noteName + " 更新路径时出错,错误信息：" + e.getMessage());
                continue;
            }
            try (FileChannel outputChannel = new RandomAccessFile(file, "rw").getChannel()) {
                MappedByteBuffer mappedByteBuffer = outputChannel.map(FileChannel.MapMode.READ_WRITE,
                        0, bytes.length);
                mappedByteBuffer.put(bytes);
            } catch (IOException e) {
                failNotes.add(noteName);
                System.out.println("Notice:  " + noteName + "  更新图片路径失败！请手动更改！ 原因：新路径无法写回文件");
            }
        }
        System.out.println("=================================================================================");
        System.out.println("运行结束！更新路径失败的文件有：");
        int count = 0;
        for (String failNote : failNotes) {
            System.out.print(failNote + "\t");
            if (count++ == 2){
                System.out.println();
                count = 0;
            }
        }
    }

    /**
     *  @MethodName replaceAll
     *  @Description  图片路径替换, JDK正则源码改编
     *  @Param [rawStr, succeedImages]
     *  @return 替换后的内容
     */
    private static String replaceAll(String rawStr, Map<String, String> ImagesInfo) {
        // 到此,ImagesInfo一定存在元素,不需再像JDK一样,提前扫描一遍
        StringBuffer sb = new StringBuffer();
        Matcher matcher = compile.matcher(rawStr);
        while (matcher.find()) {
            String s = matcher.group(0);
            if (ImagesInfo.containsKey(s)) {
                matcher.appendReplacement(sb, "vx_images/" + ImagesInfo.get(s));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}