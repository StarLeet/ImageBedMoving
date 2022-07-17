import java.io.*;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ClassName ImageMoving
 * @Description
 * @Author StarLee
 * @Date 2022/3/6
 */
public class ImageMoving {

    /**
     * 配置信息类
     */
    private static class PropertiesInfo {
        static String notesDir;
        static String notesType;
        static String fullNameReg;
        static Pattern compile;
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
            String oldImagesBedPathReg = properties.get("ImagesBedPathReg").toString();
            String imageNameReg = properties.get("ImageNameReg").toString();
            String keepOriginImage = properties.get("KeepOriginImages").toString();
            check(imageNameReg, keepOriginImage);
            KEEP_ORIGIN = "yes".equalsIgnoreCase(keepOriginImage);
            oldImagesBedPathReg = oldImagesBedPathReg.replaceAll("[/\\\\]", ".");
            if (oldImagesBedPathReg.charAt(oldImagesBedPathReg.length() - 1) != '.') {
                oldImagesBedPathReg = oldImagesBedPathReg + '.';
            }
            fullNameReg = oldImagesBedPathReg + imageNameReg;
            compile = Pattern.compile(PropertiesInfo.fullNameReg, Pattern.CASE_INSENSITIVE);
            String line = "=================================================================================";
            String s = line + "\n配置信息:\n" + "NotesDir = " + notesDir + "\nNotesType= " + notesType
                    + "\nImagesBedPathReg = " + oldImagesBedPathReg + "\nImageNameReg = " + imageNameReg
                    + "\n是否保留原图床内的图片？ " + keepOriginImage + "\n" + line;
            LogUtils.appendLog(s);
        }

        static void check(String imageNameReg, String keepOriginImage) {
            checkLength(notesDir);
            checkLength(notesType);
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
     * 多线程配置信息
     */
    private static class ConcurrentInfo {
        static final ThreadPoolExecutor cpuThreadPool;  // 匹配图片线程
        static final ThreadPoolExecutor IOThreadPool;  // IO操作线程
        static final ThreadPoolExecutor priorityIOPool;  // 备份 + 写回
        static final ConcurrentHashMap<Path, ConcurrentHashMap<String, Object>> notExistMap;  // 图片,路径
        static final Object obj;

        static {
            int coreNum = Runtime.getRuntime().availableProcessors() - 1;
            coreNum = coreNum <= 0 ? 1 : coreNum;
            cpuThreadPool = new ThreadPoolExecutor(coreNum, coreNum,
                    0L, TimeUnit.SECONDS, new LinkedBlockingDeque<>(), r -> {
                Thread t = new Thread(r);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });
            IOThreadPool = new ThreadPoolExecutor(1, 1,
                    10L, TimeUnit.SECONDS, new LinkedBlockingDeque<>());
            priorityIOPool = new ThreadPoolExecutor(3, 3,
                    0L, TimeUnit.SECONDS, new LinkedBlockingDeque<>(), r -> {
                        Thread t = new Thread(r);
                        t.setPriority(8);
                        return t;
                    });
            notExistMap = new ConcurrentHashMap<>();
            obj = new Object();
        }
    }

    /**
     * 日志信息类
     */
    private static class LogUtils {
        static FileChannel logChannel;

        static {
            Path log = Paths.get(PropertiesInfo.notesDir, "00log.txt");
            try {
               logChannel = FileChannel.open(log, StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
            System.out.println("日志文件存储在" + PropertiesInfo.notesDir);
        }

        /**
         * 线程安全
         */
        static void appendLog(String s) {
            s = s + "\n";
            try {
                logChannel.write(ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        static void appendLog(Throwable e) {
            StringBuilder sb = new StringBuilder();
            sb.append(e).append('\n');
            StackTraceElement[] trace = e.getStackTrace();
            for (StackTraceElement traceElement : trace)
                sb.append("\tat ").append(traceElement).append('\n');
            try {
                logChannel.write(ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        static void flush(){
            try {
                logChannel.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 工具类
     */
    private static class Tools {
        static volatile Method getCleaner;

        static {
            try {
                Class<?> bufferClass = Class.forName("java.nio.DirectByteBuffer");
                getCleaner = bufferClass.getMethod("cleaner");
                getCleaner.setAccessible(true);
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
        }

        static void clean(Buffer map) {
            try {
                sun.misc.Cleaner cleaner = (sun.misc.Cleaner) getCleaner.invoke(map);
                cleaner.clean();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static class MatchInfo {
        byte[] bytes;
        FileChannel open;
        MappedByteBuffer map;
        Future<Boolean> backUpFuture;

        public MatchInfo(byte[] bytes, FileChannel open,
                         MappedByteBuffer map, Future<Boolean> backUpFuture) {
            this.bytes = bytes;
            this.open = open;
            this.map = map;
            this.backUpFuture = backUpFuture;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        PropertiesInfo.loadProperties();
        long s = System.currentTimeMillis();
        handle(Paths.get(PropertiesInfo.notesDir));
        ConcurrentInfo.cpuThreadPool.shutdown();
        if (ConcurrentInfo.cpuThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)) {
            ConcurrentInfo.IOThreadPool.shutdown();
            ConcurrentInfo.priorityIOPool.shutdown();
        }
        if (ConcurrentInfo.IOThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)) {
            ConcurrentInfo.notExistMap.forEach((image, bedPaths) -> {
                LogUtils.appendLog("[INFO]" + image + " 不存在!以下为本应到达的图床:");
                bedPaths.keySet().stream().sorted(
                        Comparator.comparingInt(String::length)).forEach(LogUtils::appendLog);
            });
            LogUtils.appendLog("共耗时" + (System.currentTimeMillis() - s) / 1000.0 + "秒");
            LogUtils.flush();
        }
    }


    private static void handle(Path root) {
        AtomicBoolean flag = new AtomicBoolean(false);
        Deque<String> parents = new ArrayDeque<>();  // a stack of traveled path
        try {
            Files.walkFileTree(root, new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.toString().endsWith("vx_images") || dir.toString().endsWith("notes_bak"))
                        // skip two folders
                        return FileVisitResult.SKIP_SUBTREE;
                    flag.set(false);
                    parents.addLast(dir.toString());
                    System.out.println("[INFO]正在处理目录..." + dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    int len = (int) file.toFile().length();
                    if (file.toString().endsWith(PropertiesInfo.notesType) && len != 0) {
                        // process note
                        if (!flag.get()) {  // mkdir once
                            mkdir(parents.peekLast(), "notes_bak");
                            mkdir(parents.peekLast(), "vx_images");
                            flag.set(true);
                        }
                        Future<MatchInfo> future = ConcurrentInfo.IOThreadPool.submit(
                                readFile(parents.peekLast(), file, len));
                        ConcurrentInfo.cpuThreadPool.execute(matchTask(parents.peekLast(), future));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // 文件访问失败,跳过并打印日志
                    LogUtils.appendLog(exc);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    parents.pollLast();
                    flag.set(false);
                    System.out.println("[INFO]目录处理完成..." + dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            LogUtils.appendLog(e);
            LogUtils.flush();
            System.exit(-1);
        }
    }

    private static Callable<MatchInfo> readFile(String parent, Path file, int fileSize) {
        return () -> {
            try {
                FileChannel open = FileChannel.open(file,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                MappedByteBuffer map = open.map(FileChannel.MapMode.READ_WRITE,
                        0, fileSize);
                byte[] bytes = new byte[fileSize];
                map.get(bytes);
                Path newFile = Paths.get(parent, "notes_bak",
                        file.getFileName().toString());
                Future<Boolean> future = null;
                if (Files.notExists(newFile)) {
                    future = ConcurrentInfo.priorityIOPool.submit(backUpTask(newFile, bytes));
                }
                return new MatchInfo(bytes, open, map, future);
            } catch (Exception e) {
                LogUtils.appendLog(e);
            }
            return null;
        };
    }

    private static Callable<Boolean> backUpTask(Path newFile, byte[] bytes) {
        return () -> {
            try (FileChannel output = FileChannel.open(newFile, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                MappedByteBuffer map1 = output.map(FileChannel.MapMode.READ_WRITE,
                        0, bytes.length);
                map1.put(bytes);
                return true;
            } catch (Exception e) {
                LogUtils.appendLog(e);
                return false;
            }
        };
    }

    private static Runnable matchTask(String parent, Future<MatchInfo> future) {
        return () -> {
            MatchInfo matchInfo;
            try {
                matchInfo = future.get();
                assert matchInfo != null;
            } catch (Exception e) {
                LogUtils.appendLog(e);
                return;
            }
            // Charset charset = isGBK(matchInfo.bytes) ? Charset.forName("gbk") : StandardCharsets.UTF_8;
            Charset charset = StandardCharsets.UTF_8;
            String decode = new String(matchInfo.bytes, charset);
            Matcher matcher = PropertiesInfo.compile.matcher(decode);
            StringBuffer sb = new StringBuffer(matchInfo.bytes.length);
            Path bedPath = Paths.get(parent, "vx_images");
            while (matcher.find()) {
                String s = matcher.group(0);
                Path path = Paths.get(s);
                String imageName = path.getFileName().toString();
                moveImage(path, bedPath.toString());
                s = "vx_images/" + imageName;
                matcher.appendReplacement(sb, s);
            }
            if (sb.length() != 0) {  // 有图片才处理
                matcher.appendTail(sb);
                byte[] modifyBytes = sb.toString().getBytes(charset);
                // 以上,CPU密集
                try {
                    Boolean backUpSuccessed = true;
                    if (matchInfo.backUpFuture != null){
                        backUpSuccessed = matchInfo.backUpFuture.get();
                    }
                    if (backUpSuccessed){
                        ConcurrentInfo.priorityIOPool.execute(writeBack(matchInfo,modifyBytes));
                    }
                } catch (Exception e) {
                    LogUtils.appendLog(e);
                }
            }
        };
    }

    private static Runnable writeBack(MatchInfo matchInfo, byte[] modifyBytes){
        return () -> {
            try {
                matchInfo.map.position(0);
                matchInfo.map.put(modifyBytes);
                Tools.clean(matchInfo.map);
                matchInfo.open.truncate(modifyBytes.length);
                matchInfo.open.close();
            } catch (Exception e) {
                LogUtils.appendLog(e);
            }
        };
    }

    private static void mkdir(String s, String s1) {
        Path backupDir = Paths.get(s, s1);
        if (Files.notExists(backupDir)) {
            try {
                Files.createDirectory(backupDir);
            } catch (IOException e) {
                LogUtils.appendLog(e);
                LogUtils.flush();
                System.exit(-1);
            }
        }
    }


    private static void moveImage(Path origin, String bedPath) {
        Path target = Paths.get(bedPath, origin.getFileName().toString());
        if (Files.notExists(origin)) {  // 源图片不存在
            ConcurrentInfo.notExistMap.computeIfAbsent(origin, k -> new ConcurrentHashMap<>());
            ConcurrentInfo.notExistMap.get(origin).put(bedPath, ConcurrentInfo.obj);
            return;
        }
        try {
            ConcurrentInfo.IOThreadPool.execute(() -> {
                // single thread
                if (Files.notExists(target) || target.toFile().length() == 0) {
                    moveResource(origin, target);
                }
            });
        } catch (Exception e) {
            LogUtils.appendLog(e);
        }
    }

    /**
     * @MethodName moveResource
     * @Description 移动文件方法(通用)
     * @Param [origin, target]
     */
    private static void moveResource(Path origin, Path target) {
        try (FileChannel input = FileChannel.open(origin,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileChannel output = FileChannel.open(target,
                     StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
            long size = input.size();
            for (long left = size; left > 0; ) {
                left -= input.transferTo((size - left), left, output);
            }
        } catch (IOException e) {
            LogUtils.appendLog(e);
        }
    }
}