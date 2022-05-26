import java.io.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
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
    private static ArrayList<File> files;  // 笔记对象
    private static ArrayList<String> filesName;  // 笔记名
    private static final int PAGE_SIZE = 1 << 13;
    /**
     *  多线程配置信息
     */
    private static class ConcurrentInfo{
        static final int IO_THREAD_NUM;   // IO线程数
        static final double BLOCK_FACTOR = 0.8F;  // 阻塞因子
        static ThreadPoolExecutor threadPool;
        static ConcurrentHashMap<File,String[]> notesInfo;  // <笔记对象,<笔记内容,笔记编码>>
        static ConcurrentHashMap<String, String[]> imageNames; // <图片全路径名,<图片名,移动标记>>
        static ConcurrentHashMap<String, ArrayList<String>> imageAndFile; // <图片全路径名,所属笔记>
        static ReentrantLock readFileLock = new ReentrantLock();
        static ReentrantLock moveFileLock = new ReentrantLock();
        static ReentrantLock logLock = new ReentrantLock();
        static {
            IO_THREAD_NUM = (int)(Runtime.getRuntime().availableProcessors() / (1 - BLOCK_FACTOR));
            BlockingDeque<Runnable> blockingDeque = new LinkedBlockingDeque<>();
            threadPool = new ThreadPoolExecutor(IO_THREAD_NUM,IO_THREAD_NUM,0, TimeUnit.NANOSECONDS,
                    blockingDeque);
            notesInfo = new ConcurrentHashMap<>();
            imageNames = new ConcurrentHashMap<>();
            imageAndFile = new ConcurrentHashMap<>();
        }
    }

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
            KEEP_ORIGIN = "yes".equalsIgnoreCase(keepOriginImage);
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
            if (!"yes".equalsIgnoreCase(s) && !"no".equalsIgnoreCase(s)) {
                try {
                    throw new IllegalArgumentException("配置文件选择项应填 yes 或者 no");
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
            }
        }
    }

    /**
     *  日志信息类
     */
    private static class Log{
        static final int FILE_NOT_EXIST = 0x01;
        static final int FILE_HAS_EXIST = 0x02;
        static final int IO_EXCEPTION = 0x03;
        static ConcurrentHashMap<String,Integer> fileException;  // <图片全路径名,异常标识>
        static ConcurrentHashMap<String,String> exceptionMessage;  // <图片全路径名,异常信息>
        static Path log;
        static {
            log = Paths.get(PropertiesInfo.notesDir +"00log.txt");
            fileException = new ConcurrentHashMap<>();
            exceptionMessage = new ConcurrentHashMap<>();
            if (!log.toFile().exists()){
                try {
                    Files.createFile(log);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            System.out.println("日志文件存储在" + PropertiesInfo.notesDir);
            appendLog("=====================================================================================\n");
        }

        /**
         *  线程安全
         */
        static void appendLog(String s){
            ConcurrentInfo.logLock.lock();
            try {
                Files.write(log,s.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);  // 追加写
            } catch (IOException e) {
                e.printStackTrace();
            }finally {
                ConcurrentInfo.logLock.unlock();
            }
        }

        static void appendExceptionLog(CountDownLatch latch){
            fileException.forEach((key, value) -> ConcurrentInfo.threadPool.submit(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append(key).append(" | ").append(ConcurrentInfo.imageAndFile.get(key));
                sb.append(" | ");
                switch (value) {
                    case FILE_NOT_EXIST:
                        sb.append("移动失败 | 失败原因：图片不存在\n");
                        break;
                    case FILE_HAS_EXIST:
                        sb.append("已存在于新图床下,主动放弃迁移\n");
                        break;
                    case IO_EXCEPTION:
                        sb.append("移动失败 | 失败原因:").append(exceptionMessage.get(key)).append('\n');
                        break;
                    default:
                        break;
                }
                appendLog(sb.toString());
                latch.countDown();
            }));
        }
    }

    public static void main(String[] args) throws InterruptedException{
        long s = System.currentTimeMillis();
        PropertiesInfo.loadProperties();
        /*          单线程工作       */
        mkDirs();
        getFilesInfo();
        backupNotes();
        int pageCount = (files.size() + PAGE_SIZE - 1) / PAGE_SIZE; // 分页,得到页数
        // 防止md文件内容过多(>=1.5GB),导致JVM OOM
        for (int i = 0; i < pageCount;) {
            if (i != 0) System.gc();
            int begin = i * 1000;
            int end = Math.min(begin + 1000, files.size());
            int round = ++i;
            Log.appendLog("************************************第" + round + "轮处理************************************\n");
            CountDownLatch latch = new CountDownLatch(end - begin);
            /*          多线程工作       */
            for (int j = begin; j < end; j++) {
                final int index = j;
                ConcurrentInfo.threadPool.submit(() ->{
                    File file = files.get(index);
                    String fileName = filesName.get(index);
                    //读取指定文件路径下的文件内容
                    String[] fileData = readFile(file);
                    if (fileData == null) {  // 空白内容跳过
                        latch.countDown();
                        return;
                    }
                    Matcher matcher = compile.matcher(fileData[0]);
                    if (!matcher.find()){  // 不含图片链接跳过
                        latch.countDown();
                        return;
                    }
                    int num = collectImageNames(fileData,fileName);
                    if (num != 0){
                        ConcurrentInfo.notesInfo.put(file,fileData);
                        Log.appendLog("【" + filesName.get(index) + "中收集到" + num + "张图片等待处理中.....】\n");
                    }else {
                        files.set(index,null);
                    }
                    latch.countDown();
                });
            }
            latch.await();
            if (!ConcurrentInfo.imageNames.isEmpty()) {
                CountDownLatch latch1 = new CountDownLatch(ConcurrentInfo.imageNames.size());
                ConcurrentInfo.imageNames.forEachEntry(ConcurrentInfo.IO_THREAD_NUM,
                        (entry) -> ConcurrentInfo.threadPool.submit(() -> {
                            moveImages(entry);
                            latch1.countDown();
                        }));
                latch1.await();
            }
            if (!ConcurrentInfo.imageNames.isEmpty()){
                deleteOldImages();
                CountDownLatch latch2 = new CountDownLatch(end - begin);
                for (int j = begin; j < end; j++) {
                    if (files.get(j) == null){
                        latch2.countDown();
                    }else {
                        final String noteName = filesName.get(j);
                        final String[] fileData = ConcurrentInfo.notesInfo.get(files.get(j));
                        ConcurrentInfo.threadPool.submit(() -> {
                            updateImagePath(noteName,fileData);
                            latch2.countDown();
                        });
                    }
                }
                latch2.await();
            }
            ConcurrentInfo.notesInfo.clear();
            ConcurrentInfo.imageNames.clear();
            Log.appendLog("**********************************第" + round + "轮处理完成***********************************\n");
        }
        CountDownLatch latch = new CountDownLatch(Log.fileException.size());
        Log.appendExceptionLog(latch);
        latch.await();
        long e = System.currentTimeMillis();
        Log.appendLog("耗时：" + (e - s) / 1000.0 + "秒\n");
        ConcurrentInfo.threadPool.shutdownNow();
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
            if (fileName.endsWith(PropertiesInfo.notesType)){
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
     *  @MethodName readFile
     *  @Description  读取单个笔记
     *  @return {笔记内容,编码类型}
     */
    private static String[] readFile(File path) {
        byte[] content = null;
        try (FileChannel inputChannel = new FileInputStream(path).getChannel()) {
            if (inputChannel.size() >= Integer.MAX_VALUE) {
                Log.appendLog("Notice: " + path.getName() + "过大,请自行解决！");
                return null;
            }
            MappedByteBuffer mappedByteBuffer = inputChannel.map(FileChannel.MapMode.READ_ONLY,
                    0, inputChannel.size());
            byte[] bytes = new byte[(int) inputChannel.size()];
            mappedByteBuffer.get(bytes);
            content = bytes;
        } catch (IOException e) {
            Log.appendLog(path.getName() + e.getMessage() + " | 程序被迫中断");
            System.exit(-1);
        }
        // UTF-8可以fast-fail,既判断类型,也兼容GBK
        if (isGBK(content)) {
            try {
                return new String[]{new String(content, "gbk"), "gbk"};
            } catch (UnsupportedEncodingException e) {
                Log.appendLog(path.getName() + e.getMessage() + " | 放弃后续操作");
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
     *  @Description  并发收集笔记内容中的图片全路径名
     *  @return 收集到的图片数量
     */
    private static int collectImageNames(String[] noteInfo,String fileName) {
        int imageNum = 0;
        Matcher matcher = compile.matcher(noteInfo[0]);
        while (matcher.find()) {
            String imageFullName = matcher.group(0);
            int begin = imageFullName.length() - 4;
            while (begin > 0 && (imageFullName.charAt(--begin) != PropertiesInfo.pathChar)) ;
            String imageName = imageFullName.substring(++begin);
            ConcurrentInfo.readFileLock.lock();
            try {
                if (!ConcurrentInfo.imageNames.containsKey(imageFullName)){
                    ConcurrentInfo.imageNames.put(imageFullName,new String[]{imageName,"n"});
                    ConcurrentInfo.imageAndFile.put(imageFullName,new ArrayList<>());
                    imageNum++;
                }
                ConcurrentInfo.imageAndFile.get(imageFullName).add(fileName);
            } finally {
                ConcurrentInfo.readFileLock.unlock();
            }
        }
        return imageNum;
    }

    /**
     *  @MethodName moveImages
     *  @Description  迁移图床
     *  @Param Map.Entry<图片全路径名,<图片名,是否被移动处理过>> imageNames
     */
    @SuppressWarnings("all")
    private static void moveImages(Map.Entry<String,String[]> entry) {
        if ("y".equals(entry.getValue()[1])) return;
        ConcurrentInfo.moveFileLock.lock();
        try {
            if ("y".equals(entry.getValue()[1])) return;
            entry.getValue()[1] = "y";
        } finally {
            ConcurrentInfo.moveFileLock.unlock();
        }
        String imageFullName = entry.getKey();
        String imageName = entry.getValue()[0];
        File origin = new File(imageFullName);
        File target = new File(PropertiesInfo.imageBedPath + imageName);
        if (!Files.exists(origin.toPath())) {  // 源图片不存在
            Log.fileException.put(imageFullName,Log.FILE_NOT_EXIST);
            ConcurrentInfo.imageNames.remove(imageFullName);
        } else if (Files.exists(target.toPath()) && target.length() != 0) {  // 新图片已存在
            Log.fileException.put(imageFullName,Log.FILE_HAS_EXIST);
            ConcurrentInfo.imageNames.remove(imageFullName);
        } else {
            try {
                moveResource(origin, target);
            } catch (IOException e) {
                Log.fileException.put(imageFullName,Log.IO_EXCEPTION);
                Log.exceptionMessage.put(imageFullName,e.getMessage());
                ConcurrentInfo.imageNames.remove(imageFullName);
            }
        }
    }

    private static void deleteOldImages(){
        // 判断是移动图片，还是拷贝图片
        System.gc();
        if (!PropertiesInfo.KEEP_ORIGIN) {
            ConcurrentInfo.imageNames.keySet().forEach((path) -> {
                try {
                    Files.delete(Paths.get(path));
                } catch (IOException e) {
                    Log.appendLog(path + "移动成功！但源文件删除失败!\n");
                }
            });
        }
    }

    /**
     *  @MethodName moveResource
     *  @Description  移动文件方法(通用)
     *  @Param [origin, target]
     */
    private static void moveResource(File origin, File target) throws IOException {
        try (FileChannel inputChannel = (FileChannel) Channels.newChannel(new FileInputStream(origin));
             FileChannel outputChannel = (FileChannel) Channels.newChannel(new FileOutputStream(target))) {
            long size = inputChannel.size();
            for (long left = size; left > 0; ) {
                left -= inputChannel.transferTo((size - left), left, outputChannel);
            }
        }
        // 对移动失败,导致目标创建成空白文件的处理
        if (target.length() == 0) {
            throw new IOException("检测到移动后目标文件为空白文件");
        }
    }

    /**
     *  @MethodName updateImagePath
     *  @Description  将笔记内容中的旧路径——>新路径
     */
    private static void updateImagePath(String noteName, String[] fileData) {
        String content = replaceAll(fileData[0]);
        byte[] bytes;
        if ("gbk".equals(fileData[1])) {
            try {
                bytes = content.getBytes("gbk");
            } catch (UnsupportedEncodingException e) {
                Log.appendLog(noteName + " | 更新图片路径失败！请手动更改！ 原因：不支持的文件编码!\n");
                return;
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
            Log.appendLog(noteName + " | 更新路径时出错,错误信息：" + e.getMessage() + '\n');
            return;
        }
        try (FileChannel outputChannel = new RandomAccessFile(file, "rw").getChannel()) {
            MappedByteBuffer mappedByteBuffer = outputChannel.map(FileChannel.MapMode.READ_WRITE,
                    0, bytes.length);
            mappedByteBuffer.put(bytes);
        } catch (IOException e) {
            Log.appendLog(noteName + " | 更新图片路径失败！请手动更改！ 原因：新路径无法写回文件\n");
        }
    }

    /**
     *  @MethodName replaceAll
     *  @Description  图片路径替换, JDK正则源码改编
     *  @Param [rawStr, succeedImages]
     *  @return 替换后的内容
     */
    private static String replaceAll(String rawStr) {
        // 到此,ImagesInfo一定存在元素,不需再像JDK一样,提前扫描一遍
        StringBuffer sb = new StringBuffer();
        Matcher matcher = compile.matcher(rawStr);
        while (matcher.find()) {
            String s = matcher.group(0);
            if (ConcurrentInfo.imageNames.containsKey(s)) {
                matcher.appendReplacement(sb, "vx_images/" + ConcurrentInfo.imageNames.get(s)[0]);
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}