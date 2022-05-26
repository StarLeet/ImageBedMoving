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
    private static ArrayList<File> files;  // �ʼǶ���
    private static ArrayList<String> filesName;  // �ʼ���
    private static final int PAGE_SIZE = 1 << 13;
    /**
     *  ���߳�������Ϣ
     */
    private static class ConcurrentInfo{
        static final int IO_THREAD_NUM;   // IO�߳���
        static final double BLOCK_FACTOR = 0.8F;  // ��������
        static ThreadPoolExecutor threadPool;
        static ConcurrentHashMap<File,String[]> notesInfo;  // <�ʼǶ���,<�ʼ�����,�ʼǱ���>>
        static ConcurrentHashMap<String, String[]> imageNames; // <ͼƬȫ·����,<ͼƬ��,�ƶ����>>
        static ConcurrentHashMap<String, ArrayList<String>> imageAndFile; // <ͼƬȫ·����,�����ʼ�>
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
     *  ������Ϣ��
     */
    private static class PropertiesInfo {
        static String notesDir;
        static String notesType;
        static String imageBedPath;  // �ʼ�ͬĿ¼����ͼ��
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
            System.out.println("��ȡ��������Ϣ:\n" + "NotesDir = " + notesDir + "\nNotesType= " + notesType
                    + "\nImagesBedPathReg = " + oldImagesBedPathReg + "\nImageNameReg = " + imageNameReg
                    + "\n�Ƿ���ԭͼ���ڵ�ͼƬ�� " + keepOriginImage);
            System.out.println("=================================================================================");
        }

        static void check(String imageNameReg, String keepOriginImage) {
            checkLength(notesDir);
            if (notesDir.contains("\\")){
                throw new IllegalArgumentException("�����ļ��е�notesDir·����Ӧ��Ϊ/,������\\");
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
                    throw new IllegalArgumentException("�����ļ����Ϊ��");
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
                    throw new IllegalArgumentException("�����ļ�ѡ����Ӧ�� yes ���� no");
                } catch (IllegalArgumentException e) {
                    e.printStackTrace();
                    System.exit(-1);
                }
            }
        }
    }

    /**
     *  ��־��Ϣ��
     */
    private static class Log{
        static final int FILE_NOT_EXIST = 0x01;
        static final int FILE_HAS_EXIST = 0x02;
        static final int IO_EXCEPTION = 0x03;
        static ConcurrentHashMap<String,Integer> fileException;  // <ͼƬȫ·����,�쳣��ʶ>
        static ConcurrentHashMap<String,String> exceptionMessage;  // <ͼƬȫ·����,�쳣��Ϣ>
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
            System.out.println("��־�ļ��洢��" + PropertiesInfo.notesDir);
            appendLog("=====================================================================================\n");
        }

        /**
         *  �̰߳�ȫ
         */
        static void appendLog(String s){
            ConcurrentInfo.logLock.lock();
            try {
                Files.write(log,s.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);  // ׷��д
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
                        sb.append("�ƶ�ʧ�� | ʧ��ԭ��ͼƬ������\n");
                        break;
                    case FILE_HAS_EXIST:
                        sb.append("�Ѵ�������ͼ����,��������Ǩ��\n");
                        break;
                    case IO_EXCEPTION:
                        sb.append("�ƶ�ʧ�� | ʧ��ԭ��:").append(exceptionMessage.get(key)).append('\n');
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
        /*          ���̹߳���       */
        mkDirs();
        getFilesInfo();
        backupNotes();
        int pageCount = (files.size() + PAGE_SIZE - 1) / PAGE_SIZE; // ��ҳ,�õ�ҳ��
        // ��ֹmd�ļ����ݹ���(>=1.5GB),����JVM OOM
        for (int i = 0; i < pageCount;) {
            if (i != 0) System.gc();
            int begin = i * 1000;
            int end = Math.min(begin + 1000, files.size());
            int round = ++i;
            Log.appendLog("************************************��" + round + "�ִ���************************************\n");
            CountDownLatch latch = new CountDownLatch(end - begin);
            /*          ���̹߳���       */
            for (int j = begin; j < end; j++) {
                final int index = j;
                ConcurrentInfo.threadPool.submit(() ->{
                    File file = files.get(index);
                    String fileName = filesName.get(index);
                    //��ȡָ���ļ�·���µ��ļ�����
                    String[] fileData = readFile(file);
                    if (fileData == null) {  // �հ���������
                        latch.countDown();
                        return;
                    }
                    Matcher matcher = compile.matcher(fileData[0]);
                    if (!matcher.find()){  // ����ͼƬ��������
                        latch.countDown();
                        return;
                    }
                    int num = collectImageNames(fileData,fileName);
                    if (num != 0){
                        ConcurrentInfo.notesInfo.put(file,fileData);
                        Log.appendLog("��" + filesName.get(index) + "���ռ���" + num + "��ͼƬ�ȴ�������.....��\n");
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
            Log.appendLog("**********************************��" + round + "�ִ������***********************************\n");
        }
        CountDownLatch latch = new CountDownLatch(Log.fileException.size());
        Log.appendExceptionLog(latch);
        latch.await();
        long e = System.currentTimeMillis();
        Log.appendLog("��ʱ��" + (e - s) / 1000.0 + "��\n");
        ConcurrentInfo.threadPool.shutdownNow();
    }

    private static void mkDirs() {
        Path pathImageBed = Paths.get(PropertiesInfo.imageBedPath);
        if (!Files.exists(pathImageBed)) {  // ����ͼ��Ŀ¼
            try {
                Files.createDirectory(pathImageBed);
                System.out.println("��ͼ�������ɹ���");
            } catch (IOException e) {
                System.out.println("��ͼ������ʧ�ܣ�");
                System.exit(-1);
            }
        }
        Path backupPathNotes = Paths.get(PropertiesInfo.notesBackupPath);
        if (!Files.exists(backupPathNotes)) {  // ����ͼ��Ŀ¼
            try {
                Files.createDirectory(backupPathNotes);
                System.out.println("����Ŀ¼�����ɹ���");
            } catch (IOException e) {
                System.out.println("����Ŀ¼����ʧ��,��������ù���Ա�������bat���~");
                System.exit(-1);
            }
        }
    }
    /**
     *  ��ȡ��Ҫ����ıʼ��ļ�
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
        boolean exception = false;  //�������б��
        for (int i = 0; i < newFiles.length; i++) {
            if (Files.exists(newFiles[i].toPath()) && newFiles[i].length() != 0) continue;
            try {
                moveResource(files.get(i), newFiles[i]);
            } catch (IOException e) {
                System.out.println(filesName.get(i) + "����ʧ��");
                exception = true;
            }
        }
        if (exception) System.exit(-1);
        System.out.println("===================================���ݳɹ�========================================");
    }

    /**
     *  @MethodName readFile
     *  @Description  ��ȡ�����ʼ�
     *  @return {�ʼ�����,��������}
     */
    private static String[] readFile(File path) {
        byte[] content = null;
        try (FileChannel inputChannel = new FileInputStream(path).getChannel()) {
            if (inputChannel.size() >= Integer.MAX_VALUE) {
                Log.appendLog("Notice: " + path.getName() + "����,�����н����");
                return null;
            }
            MappedByteBuffer mappedByteBuffer = inputChannel.map(FileChannel.MapMode.READ_ONLY,
                    0, inputChannel.size());
            byte[] bytes = new byte[(int) inputChannel.size()];
            mappedByteBuffer.get(bytes);
            content = bytes;
        } catch (IOException e) {
            Log.appendLog(path.getName() + e.getMessage() + " | �������ж�");
            System.exit(-1);
        }
        // UTF-8����fast-fail,���ж�����,Ҳ����GBK
        if (isGBK(content)) {
            try {
                return new String[]{new String(content, "gbk"), "gbk"};
            } catch (UnsupportedEncodingException e) {
                Log.appendLog(path.getName() + e.getMessage() + " | ������������");
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
     *  @Description  �����ռ��ʼ������е�ͼƬȫ·����
     *  @return �ռ�����ͼƬ����
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
     *  @Description  Ǩ��ͼ��
     *  @Param Map.Entry<ͼƬȫ·����,<ͼƬ��,�Ƿ��ƶ������>> imageNames
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
        if (!Files.exists(origin.toPath())) {  // ԴͼƬ������
            Log.fileException.put(imageFullName,Log.FILE_NOT_EXIST);
            ConcurrentInfo.imageNames.remove(imageFullName);
        } else if (Files.exists(target.toPath()) && target.length() != 0) {  // ��ͼƬ�Ѵ���
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
        // �ж����ƶ�ͼƬ�����ǿ���ͼƬ
        System.gc();
        if (!PropertiesInfo.KEEP_ORIGIN) {
            ConcurrentInfo.imageNames.keySet().forEach((path) -> {
                try {
                    Files.delete(Paths.get(path));
                } catch (IOException e) {
                    Log.appendLog(path + "�ƶ��ɹ�����Դ�ļ�ɾ��ʧ��!\n");
                }
            });
        }
    }

    /**
     *  @MethodName moveResource
     *  @Description  �ƶ��ļ�����(ͨ��)
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
        // ���ƶ�ʧ��,����Ŀ�괴���ɿհ��ļ��Ĵ���
        if (target.length() == 0) {
            throw new IOException("��⵽�ƶ���Ŀ���ļ�Ϊ�հ��ļ�");
        }
    }

    /**
     *  @MethodName updateImagePath
     *  @Description  ���ʼ������еľ�·������>��·��
     */
    private static void updateImagePath(String noteName, String[] fileData) {
        String content = replaceAll(fileData[0]);
        byte[] bytes;
        if ("gbk".equals(fileData[1])) {
            try {
                bytes = content.getBytes("gbk");
            } catch (UnsupportedEncodingException e) {
                Log.appendLog(noteName + " | ����ͼƬ·��ʧ�ܣ����ֶ����ģ� ԭ�򣺲�֧�ֵ��ļ�����!\n");
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
            Log.appendLog(noteName + " | ����·��ʱ����,������Ϣ��" + e.getMessage() + '\n');
            return;
        }
        try (FileChannel outputChannel = new RandomAccessFile(file, "rw").getChannel()) {
            MappedByteBuffer mappedByteBuffer = outputChannel.map(FileChannel.MapMode.READ_WRITE,
                    0, bytes.length);
            mappedByteBuffer.put(bytes);
        } catch (IOException e) {
            Log.appendLog(noteName + " | ����ͼƬ·��ʧ�ܣ����ֶ����ģ� ԭ����·���޷�д���ļ�\n");
        }
    }

    /**
     *  @MethodName replaceAll
     *  @Description  ͼƬ·���滻, JDK����Դ��ı�
     *  @Param [rawStr, succeedImages]
     *  @return �滻�������
     */
    private static String replaceAll(String rawStr) {
        // ����,ImagesInfoһ������Ԫ��,��������JDKһ��,��ǰɨ��һ��
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