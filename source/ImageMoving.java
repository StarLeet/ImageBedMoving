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
            KEEP_ORIGIN = keepOriginImage.equalsIgnoreCase("yes");
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
            if (!s.equalsIgnoreCase("yes") && !s.equalsIgnoreCase("no")) {
                try {
                    throw new IllegalArgumentException("�����ļ�ѡ����Ӧ�� yes ���� no");
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
        int pageCount = (files.size() + pageSize - 1) / pageSize; // ��ҳ
        // ��ֹmd�ļ����ݹ���(>=1.5GB),����JVM OOM
        for (int i = 0; i < pageCount;) {
            if (i != 0) System.gc();
            int begin = i * 1000;
            int end = Math.min(begin + 1000, files.size());
            int round = ++i;
            System.out.println("************************************��" + round + "�ִ���************************************");
            Map<String, String[]> notesInfo = getFilesData(begin, end);
            Map<String, Map<String, String>> imageNames = collectImageNames(notesInfo);
            if (imageNames != null) {
                moveImages(imageNames, round);
                updateImagePath(notesInfo, imageNames);
            }
            System.out.println("**********************************��" + round + "�ִ������***********************************");
        }

    }

    private static void mkDirs() {
        Path pathImageBed = Paths.get(PropertiesInfo.imageBedPath);
        if (!Files.exists(pathImageBed)) {  // ����ͼ��Ŀ¼
            try {
                Files.createDirectory(pathImageBed);
                System.out.println("��ͼ�������ɹ���");
            } catch (IOException e) {
                System.out.println("��ͼ������ʧ�ܣ� ʧ��ԭ��" + e.getMessage());
                System.exit(-1);
            }
        }
        Path backupPathNotes = Paths.get(PropertiesInfo.notesBackupPath);
        if (!Files.exists(backupPathNotes)) {  // ����ͼ��Ŀ¼
            try {
                Files.createDirectory(backupPathNotes);
                System.out.println("����Ŀ¼�����ɹ���");
            } catch (IOException e) {
                System.out.println("����Ŀ¼����ʧ�ܣ� ʧ��ԭ��" + e.getMessage());
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
            int pointIndex = fileName.length();
            while (pointIndex > 0 && fileName.charAt(--pointIndex) != '.') ;
            // notesType�涨���ļ����Ͳſ��Լ��뼯��
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
        boolean exception = false;  //�������б��
        for (int i = 0; i < newFiles.length; i++) {
            if (Files.exists(newFiles[i].toPath()) && newFiles[i].length() != 0) continue;
            try {
                moveResource(files.get(i), newFiles[i]);
            } catch (IOException e) {
                System.out.println(filesName.get(i) + "����ʧ�ܣ� ʧ��ԭ��" + e.getMessage());
                exception = true;
            }
        }
        if (exception) System.exit(-1);
        System.out.println("===================================���ݳɹ�========================================");
    }

    /**
     *  @MethodName getFilesData
     *  @Description  ��ȡ�ʼ�����
     *  @return Map<�ʼ���,{�ʼ�����,��������}>
     */
    private static Map<String, String[]> getFilesData(int begin, int end) {
        Map<String, String[]> notesInfo = new HashMap<>();
        for (int i = begin; i < end; i++) {
            //��ȡָ���ļ�·���µ��ļ�����
            String[] fileData = readFile(files.get(i));
            // ���ݴ�С����,�������к���ͼƬ·���ļ���Map
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
     *  @Description  ��ȡ�����ʼ�
     *  @return {�ʼ�����,��������}
     */
    private static String[] readFile(File path) {
        byte[] content = null;
        try (FileChannel inputChannel = new FileInputStream(path).getChannel()) {
            if (inputChannel.size() >= Integer.MAX_VALUE) {
                System.out.println("Notice: " + path.getName() + "����,�����н����");
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
        // UTF-8����fast-fail,���ж�����,Ҳ����GBK
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
     *  @Description  �ռ����бʼ������е�ͼƬȫ·����
     *  @return Map<�ʼ���, Map<ͼƬȫ·����,ͼƬ��>>
     */
    private static Map<String, Map<String, String>> collectImageNames(Map<String, String[]> notesInfo) {
        if (notesInfo.size() == 0) {
            System.out.println("����δƥ�䵽ͼƬ���� ԭ��: 1. ���ֱʼ��в�����ͼƬ����  " +
                    "2. ���õ�(·����Ϣ|ͼƬ��������ʽ)����ȷ");
            return null;
        }
        // ������,����ͼƬȫ·����
        Map<String, Map<String, String>> imageNames = new HashMap<>();
        // ��ʼ��Map
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
        System.out.println("������ƥ�䵽" + imageNum + "��ͼƬ,���ڴ����У�");
        return imageNames;
    }

    /**
     *  @MethodName moveImages
     *  @Description  Ǩ��ͼ��
     *  @Param [Map<�ʼ���, Map<ͼƬȫ·����, ͼƬ��>>, round]
     */
    private static void moveImages(Map<String, Map<String, String>> imageNames, int round) {
        int failNum = 0;
        for (Map.Entry<String, Map<String, String>> entry : imageNames.entrySet()) {
            Map<String, String> imageNameInfo = entry.getValue();
            // ����ƶ�ʧ�ܵ�ͼƬ,�Ա��������
            ArrayList<String> failImages = new ArrayList<>(imageNameInfo.size());
            System.out.println(entry.getKey() + " ����Ҫ�����ͼƬ�У�" + imageNameInfo.size() + "��");
            // �ƶ�����
            for (Map.Entry<String, String> imageNameEntry : imageNameInfo.entrySet()) {
                String imageFullName = imageNameEntry.getKey();
                String imageName = imageNameEntry.getValue();
                File origin = new File(imageFullName);
                File target = new File(PropertiesInfo.imageBedPath + imageName);
                if (!Files.exists(origin.toPath())) {  // ԴͼƬ������
                    System.out.println(imageFullName + "  |  �ƶ�ʧ�ܣ��� |  ʧ��ԭ��ͼƬ�����ڣ�");
                    failImages.add(imageFullName);
                } else if (Files.exists(target.toPath()) && target.length() != 0) {  // ��ͼƬ�Ѵ���
                    System.out.println(imageFullName + "  �Ѵ�������ͼ����,��������Ǩ��~~~");
                } else {
                    try {
                        moveResource(origin, target);
                    } catch (IOException e) {
                        System.out.println(imageFullName + "  |  �ƶ�ʧ�ܣ��� |  ʧ��ԭ��" + e.getMessage());
                        failImages.add(imageFullName);
                    }
                }
            }
            // ���ƶ�ʧ�ܵ�ͼƬ��ͼƬ�������Ƴ�
            failNum += failImages.size();
            for (String failImage : failImages) {
                imageNameInfo.remove(failImage);
            }
            System.out.println("=================================================================================");
        }
        boolean beEmpty = true;
        // ���ʧ�ܲ��������Ŀ�Map
        for (Map.Entry<String, Map<String, String>> entry : imageNames.entrySet()) {
            if (entry.getValue().isEmpty()){
                imageNames.remove(entry.getKey());
                continue;
            }
            beEmpty = false;
        }
        if (beEmpty){
            System.out.println("Notice�����ƶ��ɹ���ͼƬ,���������������壡");
            System.exit(-1);
        }
        // �ж����ƶ�ͼƬ�����ǿ���ͼƬ
        if (!PropertiesInfo.KEEP_ORIGIN) {
            System.gc();
            for (Map<String, String> names : imageNames.values()) {
                for (String imageFullName : names.keySet()) {
                    try {
                        Files.delete(Paths.get(imageFullName));
                    } catch (IOException e) {
                        System.out.println(imageFullName + "�ƶ��ɹ�����Դ�ļ�ɾ��ʧ��!");
                    }
                }
            }
        }
        System.out.println("��" + round + "�����մ���ʧ�ܵ�ͼƬΪ" + failNum + "�ţ�");
    }

    /**
     *  @MethodName moveResource
     *  @Description  �ƶ��ļ�����(ͨ��)
     *  @Param [origin, target]
     */
    private static void moveResource(File origin, File target) throws IOException {
        try (FileChannel inputChannel = (FileChannel) Channels.newChannel(new FileInputStream(origin));
             FileChannel outputChannel = (FileChannel) Channels.newChannel(new FileOutputStream(target))) {
            inputChannel.transferTo(0, inputChannel.size(), outputChannel);
        }
        // ���ƶ�ʧ��,����Ŀ�괴���ɿհ��ļ��Ĵ���
        if (target.length() == 0) {
            System.out.println(target + "  ���ƶ������г����쳣,����ʾΪ�հ��ļ����Է�����,������ԭ�ļ����ֶ��ƶ���������");
            throw new IOException();
        }
    }

    /**
     *  @MethodName updateImagePath
     *  @Description  ���ʼ������еľ�·������>��·��
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
                    System.out.println("Notice:  " + noteName + "  ����ͼƬ·��ʧ�ܣ����ֶ����ģ� ԭ�򣺲�֧�ֵ��ļ�����");
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
                System.out.println(noteName + " ����·��ʱ����,������Ϣ��" + e.getMessage());
                continue;
            }
            try (FileChannel outputChannel = new RandomAccessFile(file, "rw").getChannel()) {
                MappedByteBuffer mappedByteBuffer = outputChannel.map(FileChannel.MapMode.READ_WRITE,
                        0, bytes.length);
                mappedByteBuffer.put(bytes);
            } catch (IOException e) {
                failNotes.add(noteName);
                System.out.println("Notice:  " + noteName + "  ����ͼƬ·��ʧ�ܣ����ֶ����ģ� ԭ����·���޷�д���ļ�");
            }
        }
        System.out.println("=================================================================================");
        System.out.println("���н���������·��ʧ�ܵ��ļ��У�");
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
     *  @Description  ͼƬ·���滻, JDK����Դ��ı�
     *  @Param [rawStr, succeedImages]
     *  @return �滻�������
     */
    private static String replaceAll(String rawStr, Map<String, String> ImagesInfo) {
        // ����,ImagesInfoһ������Ԫ��,��������JDKһ��,��ǰɨ��һ��
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