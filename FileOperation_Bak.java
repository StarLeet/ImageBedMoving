import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @ClassName FileOperation_Bak
 * @Description
 * @Author StarLee
 * @Date 2022/3/6
 */

public class FileOperation_Bak {
    private static String notesDir;
    private static String oldImagesBedPathReg;
    private static String fullNameReg;
    private static String imageBedPath;  // �ʼ�ͬĿ¼����ͼ��
    private static String notesBackupPath;

    public static void main(String[] args) {
        loadProperties();
        File imageBed = new File(imageBedPath);
        if (!imageBed.exists()){  // ����ͼ��Ŀ¼
            while (!imageBed.mkdir()){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        File notesBackup = new File(notesBackupPath);
        if (!notesBackup.exists()){  // ����ͼ��Ŀ¼
            while (!notesBackup.mkdir()){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        File file = new File(notesDir); //��Ҫ��ȡ���ļ���·��
        String[] fileNameLists = file.list(); // ��ȡ���еıʼ���
        File[] filePathLists = file.listFiles(); // ���������ʼǶ����Ա��ȡ����
        assert fileNameLists != null && filePathLists != null;
        Map<String,StringBuilder> notesInfo = getFilesData(fileNameLists,filePathLists);
        Map<String,ArrayList<String[]>> imageNames = collectImageNames(notesInfo);

        int succeedNum = 0;  // ��¼����ɹ���ͼƬ
        for (String fileName : imageNames.keySet()) {
            ArrayList<String[]> imageNameInfo = imageNames.get(fileName);
            System.out.println(fileName + " ����Ҫ�����ͼƬ�У�" + imageNameInfo.size() + "��");
            for (String[] strings : imageNameInfo) {
                if (moveFile(strings[0],imageBedPath + "\\" + strings[1])){
                    succeedNum++;
                }
            }
            System.out.println("============================================");
        }
        System.out.println("���մ���ɹ���ͼƬΪ" + succeedNum + "�ţ�");
        backupNotes(notesInfo,notesBackupPath,notesDir);
    }

    private static void loadProperties(){
        Properties properties = new Properties();
        try (FileInputStream fileInputStream = new FileInputStream("ImageMoving.properties");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(fileInputStream,StandardCharsets.UTF_8))){
            properties.load(bufferedReader);
        } catch (IOException e) {
            e.printStackTrace();
        }
        notesDir = properties.get("NotesDir").toString();
        oldImagesBedPathReg = properties.get("ImagesBedPathReg").toString();
        String imageNameReg = properties.get("ImageNameReg").toString();
        assert notesDir == null || oldImagesBedPathReg == null || imageNameReg == null;
        fullNameReg = oldImagesBedPathReg + imageNameReg;
        imageBedPath = notesDir + "\\vx_images";
        notesBackupPath = notesDir + "\\notes_bak";
        System.out.println("=============================================================");
        System.out.println("��ȡ��������Ϣ:\n" + "NotesDir = " + notesDir
                + "\nImagesBedPathReg = " + oldImagesBedPathReg + "\nImageNameReg = " + imageNameReg);
        System.out.println("=============================================================");
    }

    /**
     *  @MethodName getFilesData
     *  @Description
     *  @Param [filePath]
     *  @return Map<String �ʼ���,StringBuilder �ʼ�����>
     */
    public static Map<String, StringBuilder> getFilesData(String[] fileNameLists,File[] filePathLists){
        Map<String, StringBuilder> notesInfo = new HashMap<>();
        for(int i = 0; i < filePathLists.length; i++){
            if(filePathLists[i].isFile()){
                //��ȡָ���ļ�·���µ��ļ�����
                StringBuilder fileData = readFile(filePathLists[i]);
                //���ļ�����Ϊkey,�ļ�����Ϊvalue �洢��map��
                notesInfo.put(fileNameLists[i], fileData);
            }
        }
        return notesInfo;
    }

    /**
     *  @MethodName readFile
     *  @Description  ��ȡ�����ļ�������
     *  @Param [path]
     *  @return String ����ȡ�������ݷ���
     */
    public static StringBuilder readFile(File path){
        //����һ������������
        StringBuilder sb = new StringBuilder();
        // try-with-resource �﷨��
        try (FileInputStream fileInputStream = new FileInputStream(path);
             BufferedReader  bufferedReader = new BufferedReader(new InputStreamReader(
                fileInputStream, StandardCharsets.UTF_8)) ){
            String line;
            while((line = bufferedReader.readLine()) != null){
                //������ת��Ϊ�ַ���
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sb;
    }

    /**
     *  @MethodName collectImageNames
     *  @Description  �ռ���ǰĿ¼�����бʼ��ڵ�ͼƬ����·��
     *  @Param [notesInfo]
     *  @return Map<String,ArrayList<String[]>>
     *      ��ÿ��ͼƬ��[�ʼ���,ͼƬȫ·��ȫ��|ͼƬ��]��ʽ���浽Map��
     */
    public static Map<String,ArrayList<String[]>> collectImageNames(Map<String,StringBuilder> notesInfo){
        Map<String,ArrayList<String[]>> imageNames = new HashMap<>();
        // ��ʼ��Map
        for (String fileName : notesInfo.keySet()) {
            imageNames.put(fileName,new ArrayList<>());
        }
        Pattern compile = Pattern.compile(fullNameReg);
        int imageNum = 0;
        for (String fileName : notesInfo.keySet()) {
            Matcher matcher = compile.matcher(notesInfo.get(fileName));
            while (matcher.find()){
                String imageFullName = matcher.group(0);
                String[] split = imageFullName.split("\\\\");
                String imageName = split[split.length - 1];
                imageNames.get(fileName).add(new String[]{imageFullName,imageName});
                imageNum++;
            }
        }
        if (imageNum == 0){
            System.out.println("δƥ�䵽ͼƬ�������ѣ��Ƿ�collectImageNames()�е�����ƥ��ʽûд�ԣ�");
            System.exit(-1);
        }else {
            System.out.println("��ƥ�䵽" + imageNum + "��ͼƬ,���ڴ����У�");
        }
        return imageNames;
    }

    public static boolean moveFile(String oldPathName,String newPathName){
        File oldFile = new File(oldPathName);
        File newFile = new File(newPathName);
        boolean result = oldFile.renameTo(newFile);
        System.out.println(oldFile + " ����> " + newFile + " result: " + result);
        return result;
    }

    public static void backupNotes(Map<String,StringBuilder> notesInfo, String notesBackupPath, String notesDir) {
        System.out.println("==========XXXXXXX=========");
        System.out.println("�ѱ���ԭ�ʼǣ������Խ�ԭͼƬ·������Ϊvx_images/......");
        for (String noteName : notesInfo.keySet()) {
            File file = new File(notesBackupPath + "\\" + noteName);
            if (!file.exists()){
                try(FileOutputStream fileOutputStream = new FileOutputStream(file);
                    BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(
                            fileOutputStream, StandardCharsets.UTF_8))){
                    bufferedWriter.write(notesInfo.get(noteName).toString());
                }catch (IOException e){
                    e.printStackTrace();
                }
            }
            updateImagePath(notesInfo,noteName);
            try(FileOutputStream fileOutputStream = new FileOutputStream(notesDir + "\\" + noteName);
                BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(
                        fileOutputStream, StandardCharsets.UTF_8))) {
                bufferedWriter.write(notesInfo.get(noteName).toString());
            }catch (IOException e){
                e.printStackTrace();
            }
        }
        System.out.println("���н�������Աʼ����ݽ��������飡");
    }

    public static void updateImagePath(Map<String,StringBuilder> notesInfo, String noteName){
        StringBuilder sb = notesInfo.get(noteName);
        if (sb == null) return;
        notesInfo.put(noteName,new StringBuilder(sb.toString().replaceAll(
                oldImagesBedPathReg, "vx_images/")));
    }
}
