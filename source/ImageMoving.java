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
        static String imageBedPath;  // �ʼ�ͬĿ¼����ͼ��
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
            System.out.println("��ȡ��������Ϣ:\n" + "NotesDir = " + notesDir + "\nNotesType= " + notesType
                    + "\nImagesBedPathReg = " + oldImagesBedPathReg + "\nImageNameReg = " + imageNameReg
                    + "\n�ʼ��Ƿ�ȫΪUTF8���� = " + encodingUTF8 + "\n�Ƿ����ִ�Сд�� " + ignoreCase
                    + "\n�Ƿ���ԭͼ���ڵ�ͼƬ�� " + keepOriginImage);
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
        for (int i = 0; i < pageCount; i++) {
            int round = i + 1;
            System.out.println("************************************��" + round + "�ִ���************************************");
            int begin = i * 1000;
            int end = Math.min(begin + 1000, files.size());
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
     * @MethodName getFilesData
     * @Description
     */
    private static Map<String, String[]> getFilesData(int begin, int end) {
        Map<String, String[]> notesInfo = new HashMap<>();
        for (int i = begin; i < end; i++) {
            //��ȡָ���ļ�·���µ��ļ�����
            String[] fileData = readFile(files.get(i));
            //���ļ�����Ϊkey,�ļ�����Ϊvalue �洢��map��
            if (fileData != null) {
                notesInfo.put(filesName.get(i), fileData);
            }
        }
        return notesInfo;
    }

    /**
     * @return String ����ȡ�������ݷ���
     * @MethodName readFile
     * @Description ��ȡ�����ļ�������
     * @Param [path]
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
//                continue;   // ֻ��һ���ֽ�
//            } else if ((temp & 0x40) == 0x40 && (temp & 0x20) == 0) {// 110xxxxx 10xxxxxx
//                // �������ֽ�
//                if (i + 1 < end && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0) {
//                    continue;
//                }
//            } else if ((temp & 0xE0) == 0xE0 && (temp & 0x10) == 0) {// 1110xxxx 10xxxxxx 10xxxxxx
//                // �������ֽ�
//                if (i + 2 < end && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0
//                        && (buffer[++i] & 0x80) == 0x80 && (buffer[i] & 0x40) == 0) {
//                    continue;
//                }
//            } else if ((temp & 0xF0) == 0xF0 && (temp & 0x08) == 0) {// 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
//                // ���ĸ��ֽ�
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
     * ��ÿ��ͼƬ��[�ʼ���,ͼƬȫ·��ȫ��|ͼƬ��]��ʽ���浽Map��
     * @MethodName collectImageNames
     * @Description �ռ���ǰĿ¼�����бʼ��ڵ�ͼƬ����·��
     * @Param [notesInfo]
     */
    private static Map<String, Map<String, String>> collectImageNames(Map<String, String[]> notesInfo) {
        Map<String, Map<String, String>> imageNames = new HashMap<>();
        // ��ʼ��Map
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
            System.out.println("����δƥ�䵽ͼƬ���� ԭ��: 1. ���ֱʼ��в�����ͼƬ����  " +
                    "2. ���õ�(·����Ϣ|ͼƬ��������ʽ)����ȷ");
            return null;
        } else {
            System.out.println("������ƥ�䵽" + imageNum + "��ͼƬ,���ڴ����У�");
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
            System.out.println(entry.getKey() + " ����Ҫ�����ͼƬ�У�" + imageNameInfo.size() + "��");
            for (Map.Entry<String, String> imageNameEntry : imageNameInfo.entrySet()) {
                String imageFullName = imageNameEntry.getKey();
                String imageName = imageNameEntry.getValue();
                File origin = new File(imageFullName);
                File target = new File(PropertiesInfo.imageBedPath + imageName);
                if (!Files.exists(origin.toPath())) {
                    System.out.println(imageFullName + "  |  �ƶ�ʧ�ܣ��� |  ʧ��ԭ��ͼƬ�����ڣ�");
                    failImages.add(imageFullName);
                } else if (Files.exists(target.toPath()) && target.length() != 0) {
                    System.out.println(imageFullName + "  �Ѵ�������ͼ����,��������Ǩ��~~~");
                } else {
                    try {
                        moveResource(origin, target);
                    } catch (IOException e) {
                        System.out.println(imageFullName + "  |  �ƶ�ʧ�ܣ���");
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
            System.out.println(target + "  ���ƶ������г����쳣,����ʾΪ�հ��ļ����Է�����,������ԭ�ļ����ֶ��ƶ���������");
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
                    System.out.println("Notice:  " + noteName + "  ����ͼƬ·��ʧ�ܣ����ֶ����ģ� ԭ�򣺲�֧�ֵ��ļ�����");
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
                System.out.println("Notice:  " + noteName + "  ����ͼƬ·��ʧ�ܣ����ֶ����ģ� ԭ����·���޷�д���ļ�");
            }
        }
        System.out.println("=================================================================================");
        System.out.println("���н������ɹ�д��(����)���ļ��У�");
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
