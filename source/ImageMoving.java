import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private static String notesDir;
    private static String notesType;
    private static String imageBedPath;  // 笔记同目录建立图床
    private static String notesBackupPath;
    private static String oldImagesBedPathReg;
    private static String fullNameReg;
    private static boolean CaseSensitive;
    private static boolean KEEP_ORIGIN;

    public static void main(String[] args) {
        loadProperties();
        mkDirs();
        File file = new File(notesDir); //需要获取的文件的路径
        String[] fileNameLists = file.list(); // 获取所有的笔记名
        File[] filePathLists = file.listFiles(); // 批量创建笔记对象，以便读取内容
        assert fileNameLists != null && filePathLists != null;
        backupNotes(fileNameLists,filePathLists);
        Map<String,String[]> notesInfo = getFilesData(fileNameLists,filePathLists);
        Map<String,ArrayList<String[]>> imageNames = collectImageNames(notesInfo);
        int succeedNum = moveImages(imageNames);  // 记录处理成功的图片
        System.out.println("==============================总计处理成功的图片:" + succeedNum +"张===============================");
        if (succeedNum == 0) return;
        updateImagePath(notesInfo,imageNames);
    }

    /**
     *  @MethodName loadProperties
     *  @Description  加载配置文件
     *  @Param []
     *  @return void
     */
    private static void loadProperties(){
        Properties properties = new Properties();
        try (FileInputStream fileInputStream = new FileInputStream("../ImageMoving.properties");
             BufferedReader bufferedReader = new BufferedReader(
                     new InputStreamReader(fileInputStream, StandardCharsets.UTF_8))){
            properties.load(bufferedReader);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        notesDir = properties.get("NotesDir").toString();
        notesType = properties.get("NotesType").toString();
        oldImagesBedPathReg = properties.get("ImagesBedPathReg").toString();
        String imageNameReg = properties.get("ImageNameReg").toString();
        String ignoreCase = properties.get("IgnoreCase").toString();
        String keepOriginImage = properties.get("KeepOriginImages").toString();
        checkValid(imageNameReg,ignoreCase,keepOriginImage);
        CaseSensitive = ignoreCase.equalsIgnoreCase("yes");
        KEEP_ORIGIN = keepOriginImage.equalsIgnoreCase("yes");
        fullNameReg = oldImagesBedPathReg + imageNameReg;
        imageBedPath = notesDir + "\\vx_images";
        notesBackupPath = notesDir + "\\notes_bak";
        System.out.println("===================================读取配置成功====================================");
        System.out.println("读取到配置信息:\n" + "NotesDir = " + notesDir + "\nNotesType= " + notesType
                + "\nImagesBedPathReg = " + oldImagesBedPathReg + "\nImageNameReg = " + imageNameReg
                + "\n是否区分大小写？ " + ignoreCase + "\n是否保留原图床内的图片？ " + keepOriginImage);
        System.out.println("===================================================================================");
    }
    /**
     *  @MethodName checkValid
     *  @Description  检测配置文件是否有效
     *  @Param [imageNameReg, ignoreCase, keepOriginImage]
     *  @return void
     */
    private static void checkValid(String imageNameReg, String ignoreCase, String keepOriginImage){
        if (notesDir.length() == 0){
            try {
                throw new IllegalArgumentException("配置文件NotesDir项不可为空");
            }catch (IllegalArgumentException e){
                e.printStackTrace();
                System.exit(-1);
            }
        }
        if (notesType.length() == 0){
            try {
                throw new IllegalArgumentException("配置文件notesType项不可为空");
            }catch (IllegalArgumentException e){
                e.printStackTrace();
                System.exit(-1);
            }
        }
        if (oldImagesBedPathReg.length() == 0){
            try {
                throw new IllegalArgumentException("配置文件ImagesBedPathReg项不可为空");
            }catch (IllegalArgumentException e){
                e.printStackTrace();
                System.exit(-1);
            }
        }
        if (imageNameReg.length() == 0){
            try {
                throw new IllegalArgumentException("配置文件ImageNameReg项不可为空");
            }catch (IllegalArgumentException e){
                e.printStackTrace();
                System.exit(-1);
            }
        }
        if (!ignoreCase.equalsIgnoreCase("yes") && !ignoreCase.equalsIgnoreCase("no")){
            try {
                throw new IllegalArgumentException("配置文件caseSensitive项不合法");
            }catch (IllegalArgumentException e){
                e.printStackTrace();
                System.exit(-1);
            }
        }
        if (!keepOriginImage.equalsIgnoreCase("yes") && !keepOriginImage.equalsIgnoreCase("no")){
            try {
                throw new IllegalArgumentException("配置文件keepOriginImage项不合法");
            }catch (IllegalArgumentException e){
                e.printStackTrace();
                System.exit(-1);
            }
        }
    }

    private static void mkDirs(){
        File imageBed = new File(imageBedPath);
        if (!imageBed.exists()){  // 创建图床目录
            if (imageBed.mkdir()){
                System.out.println("==================================新图床创建成功====================================");
            }else {
                System.out.println("新图床创建失败,或许可以用管理员身份运行bat解决~");
                System.exit(-1);
            }
        }
        File notesBackup = new File(notesBackupPath);
        if (!notesBackup.exists()){  // 创建图床目录
            if (notesBackup.mkdir()){
                System.out.println("==================================备份目录创建成功==================================");
            }else {
                System.out.println("备份目录创建失败,或许可以用管理员身份运行bat解决~");
                System.exit(-1);
            }
        }
    }

    private static void backupNotes(String[] oldFileNames,File[] oldFiles){
        File[] newFiles = new File[oldFiles.length];
        for (int i = 0; i < newFiles.length; i++) {
            newFiles[i] = new File(notesBackupPath + "\\" + oldFileNames[i]);
        }
        boolean exception = false;
        for (int i = 0; i < oldFiles.length; i++) {
            if (oldFiles[i].isFile() && !newFiles[i].exists()){
                int begin = oldFileNames[i].length();
                while (oldFileNames[i].charAt(--begin) != '.');
                if (!oldFileNames[i].substring(begin + 1).equalsIgnoreCase(notesType)) continue;
                try (RandomAccessFile oldFile = new RandomAccessFile(oldFiles[i],"r");
                     RandomAccessFile newFile = new RandomAccessFile(newFiles[i],"rw");
                     FileChannel oldFileChannel = oldFile.getChannel();
                     FileChannel newFileChannel = newFile.getChannel()){
                    MappedByteBuffer mappedByteBuffer = oldFileChannel.map(FileChannel.MapMode.READ_ONLY,
                            0, oldFileChannel.size());
                    newFileChannel.write(mappedByteBuffer.asReadOnlyBuffer());
                }catch (IOException e){
                    System.out.println(oldFileNames[i] + "备份失败");
                    exception = true;
                }
            }
        }
        if (exception) System.exit(-1);
        System.out.println("===================================备份成功========================================");
    }

    /**
     *  @MethodName getFilesData
     *  @Description
     */
    private static Map<String, String[]> getFilesData(String[] fileNameLists,File[] filePathLists){
        Map<String, String[]> notesInfo = new HashMap<>();
        for(int i = 0; i < filePathLists.length; i++){
            if(filePathLists[i].isFile()){
                int begin = fileNameLists[i].length();
                while (fileNameLists[i].charAt(--begin) != '.');
                if (fileNameLists[i].substring(begin + 1).equalsIgnoreCase(notesType)){
                    //读取指定文件路径下的文件内容
                    String[] fileData = readFile(filePathLists[i]);
                    //把文件名作为key,文件内容为value 存储在map中
                    if (fileData != null){
                        notesInfo.put(fileNameLists[i], fileData);
                    }
                }
            }
        }
        return notesInfo;
    }

    /**
     *  @MethodName readFile
     *  @Description  读取单个文件的内容
     *  @Param [path]
     *  @return String 将读取到的内容返回
     */
    private static String[] readFile(File path){
        byte[] content = null;
        try (RandomAccessFile r = new RandomAccessFile(path, "r");
             FileChannel rwChannel = r.getChannel()){
            MappedByteBuffer mappedByteBuffer = rwChannel.map(FileChannel.MapMode.READ_ONLY,
                    0, r.length());
            if (r.length() > Integer.MAX_VALUE){
                System.out.println("Notice: " + path.getName() + "过大,请自行解决！");
                return null;
            }
            byte[] bytes = new byte[(int) r.length()];
            mappedByteBuffer.get(bytes);
            content = bytes;
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        if (isGBK(content)){
            try {
                return new String[]{new String(content, "gbk"),"gbk"};
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        return new String[]{new String(content, StandardCharsets.UTF_8),"utf8"};
    }

    /**
     * from Website
     */
    private static boolean isGBK(byte[] buffer){
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
     *  @Description  收集当前目录下所有笔记内的图片引用路径
     *  @Param [notesInfo]
     *  @return Map<String,ArrayList<String[]>>
     *      将每张图片以[笔记名,图片全路径全名|图片名]形式保存到Map中
     */
    private static Map<String,ArrayList<String[]>> collectImageNames(Map<String,String[]> notesInfo){
        Map<String,ArrayList<String[]>> imageNames = new HashMap<>();
        // 初始化Map
        for (String fileName : notesInfo.keySet()) {
            imageNames.put(fileName,new ArrayList<>());
        }
        Pattern compile = CaseSensitive ? Pattern.compile(fullNameReg)
                : Pattern.compile(fullNameReg,Pattern.CASE_INSENSITIVE);
        int imageNum = 0;
        for (Map.Entry<String, String[]> entry : notesInfo.entrySet()) {
            Matcher matcher = compile.matcher(entry.getValue()[0]);
            while (matcher.find()){
                String imageFullName = matcher.group(0);
                int begin = imageFullName.length() - 4;
                while (begin > 0 && (imageFullName.charAt(--begin) != '\\' &&
                        imageFullName.charAt(begin) != '/'));
                String imageName = imageFullName.substring(++begin);
                imageNames.get(entry.getKey()).add(new String[]{imageFullName,imageName});
                imageNum++;
            }
        }
        if (imageNum == 0){
            System.out.println("未匹配到图片名。提醒：请检查ImageMoving.properties配置文件");
            System.exit(-1);
        }else {
            System.out.println("=========================已匹配到" + imageNum + "张图片,正在处理中=================================");
        }
        return imageNames;
    }

    private static int moveImages(Map<String,ArrayList<String[]>> imageNames){
        int succeedNum = 0;
        for (Map.Entry<String, ArrayList<String[]>> entry : imageNames.entrySet()) {
            ArrayList<String[]> imageNameInfo = entry.getValue();
            System.out.println(entry.getKey() + " 中需要处理的图片有：" + imageNameInfo.size() + "张");
            for (String[] strings : imageNameInfo) {
                File origin = new File(strings[0]);
                File target = new File(imageBedPath + "\\" + strings[1]);
                if (!origin.exists()){
                    System.out.println(strings[0] + "  |  移动失败！！ |  失败原因：" + "图片不存在！");
                    continue;
                }else if (target.exists()) {
                    System.out.println(strings[0] + "  已存在于新图床下,主动放弃迁移~~~");
                    continue;
                }
                if (KEEP_ORIGIN){
                    try (RandomAccessFile originImage = new RandomAccessFile(origin,"r");
                         RandomAccessFile targetImage = new RandomAccessFile(target,"rw");
                         FileChannel inputChannel = originImage.getChannel();
                         FileChannel outputChannel = targetImage.getChannel()){
                        MappedByteBuffer mappedByteBuffer = inputChannel.map(FileChannel.MapMode.READ_ONLY,
                                0, inputChannel.size());
                        outputChannel.write(mappedByteBuffer.asReadOnlyBuffer());
                    }catch (IOException e){
                        System.out.println(strings[0] + "  |  移动失败！！");
                        continue;
                    }
                }else {
                    try {
                        Files.move(Paths.get(origin.getPath()),Paths.get(target.getPath()));
                    } catch (IOException e) {
                        System.out.println(strings[0] + "  |  移动失败！！");
                        continue;
                    }
                }
                succeedNum++;
            }
            System.out.println("===================================================================================");
        }
        return succeedNum;
    }

    private static void updateImagePath(Map<String,String[]> notesInfo, Map<String,ArrayList<String[]>> imageNames){
        ArrayList<String> succeedNotes = new ArrayList<>();
        for (Map.Entry<String, String[]> entry : notesInfo.entrySet()) {
            String noteName = entry.getKey();
            if (imageNames.get(noteName).isEmpty()) continue;
            String content = entry.getValue()[0].replaceAll(oldImagesBedPathReg, "vx_images/");
            byte[] bytes;
            if (entry.getValue()[1].equals("gbk")){
                try {
                    bytes = content.getBytes("gbk");
                } catch (UnsupportedEncodingException e) {
                    System.out.println("Notice:  " + noteName + "  更新图片路径失败！请手动更改！ 原因：字符串转字节数组出现问题");
                    continue;
                }
            }else {
                bytes = content.getBytes(StandardCharsets.UTF_8);
            }
            try (RandomAccessFile rw = new RandomAccessFile(notesDir + "\\" + noteName, "rw");
                 FileChannel rwChannel = rw.getChannel()){
                MappedByteBuffer mappedByteBuffer = rwChannel.map(FileChannel.MapMode.READ_WRITE,
                        0, bytes.length);
                mappedByteBuffer.put(bytes);
                succeedNotes.add(noteName);
            } catch (IOException e) {
                System.out.println("Notice:  " + noteName + "  更新图片路径失败！请手动更改！ 原因：新路径无法写回文件");
            }
        }
        System.out.println("===================================================================================");
        System.out.println("运行结束！成功写回(更新)的文件有：");
        for (String succeedNote : succeedNotes) {
            System.out.println(succeedNote);
        }
    }
}
