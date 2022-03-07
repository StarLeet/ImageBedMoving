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
    private static String imageBedPath;  // 笔记同目录建立图床
    private static String notesBackupPath;

    public static void main(String[] args) {
        loadProperties();
        File imageBed = new File(imageBedPath);
        if (!imageBed.exists()){  // 创建图床目录
            while (!imageBed.mkdir()){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        File notesBackup = new File(notesBackupPath);
        if (!notesBackup.exists()){  // 创建图床目录
            while (!notesBackup.mkdir()){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        File file = new File(notesDir); //需要获取的文件的路径
        String[] fileNameLists = file.list(); // 获取所有的笔记名
        File[] filePathLists = file.listFiles(); // 批量创建笔记对象，以便读取内容
        assert fileNameLists != null && filePathLists != null;
        Map<String,StringBuilder> notesInfo = getFilesData(fileNameLists,filePathLists);
        Map<String,ArrayList<String[]>> imageNames = collectImageNames(notesInfo);

        int succeedNum = 0;  // 记录处理成功的图片
        for (String fileName : imageNames.keySet()) {
            ArrayList<String[]> imageNameInfo = imageNames.get(fileName);
            System.out.println(fileName + " 中需要处理的图片有：" + imageNameInfo.size() + "张");
            for (String[] strings : imageNameInfo) {
                if (moveFile(strings[0],imageBedPath + "\\" + strings[1])){
                    succeedNum++;
                }
            }
            System.out.println("============================================");
        }
        System.out.println("最终处理成功的图片为" + succeedNum + "张！");
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
        System.out.println("读取到配置信息:\n" + "NotesDir = " + notesDir
                + "\nImagesBedPathReg = " + oldImagesBedPathReg + "\nImageNameReg = " + imageNameReg);
        System.out.println("=============================================================");
    }

    /**
     *  @MethodName getFilesData
     *  @Description
     *  @Param [filePath]
     *  @return Map<String 笔记名,StringBuilder 笔记内容>
     */
    public static Map<String, StringBuilder> getFilesData(String[] fileNameLists,File[] filePathLists){
        Map<String, StringBuilder> notesInfo = new HashMap<>();
        for(int i = 0; i < filePathLists.length; i++){
            if(filePathLists[i].isFile()){
                //读取指定文件路径下的文件内容
                StringBuilder fileData = readFile(filePathLists[i]);
                //把文件名作为key,文件内容为value 存储在map中
                notesInfo.put(fileNameLists[i], fileData);
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
    public static StringBuilder readFile(File path){
        //创建一个输入流对象
        StringBuilder sb = new StringBuilder();
        // try-with-resource 语法糖
        try (FileInputStream fileInputStream = new FileInputStream(path);
             BufferedReader  bufferedReader = new BufferedReader(new InputStreamReader(
                fileInputStream, StandardCharsets.UTF_8)) ){
            String line;
            while((line = bufferedReader.readLine()) != null){
                //把数据转换为字符串
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sb;
    }

    /**
     *  @MethodName collectImageNames
     *  @Description  收集当前目录下所有笔记内的图片引用路径
     *  @Param [notesInfo]
     *  @return Map<String,ArrayList<String[]>>
     *      将每张图片以[笔记名,图片全路径全名|图片名]形式保存到Map中
     */
    public static Map<String,ArrayList<String[]>> collectImageNames(Map<String,StringBuilder> notesInfo){
        Map<String,ArrayList<String[]>> imageNames = new HashMap<>();
        // 初始化Map
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
            System.out.println("未匹配到图片名。提醒：是否collectImageNames()中的正则匹配式没写对？");
            System.exit(-1);
        }else {
            System.out.println("已匹配到" + imageNum + "个图片,正在处理中！");
        }
        return imageNames;
    }

    public static boolean moveFile(String oldPathName,String newPathName){
        File oldFile = new File(oldPathName);
        File newFile = new File(newPathName);
        boolean result = oldFile.renameTo(newFile);
        System.out.println(oldFile + " ——> " + newFile + " result: " + result);
        return result;
    }

    public static void backupNotes(Map<String,StringBuilder> notesInfo, String notesBackupPath, String notesDir) {
        System.out.println("==========XXXXXXX=========");
        System.out.println("已备份原笔记！正尝试将原图片路径，改为vx_images/......");
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
        System.out.println("运行结束！请对笔记内容进行随机检查！");
    }

    public static void updateImagePath(Map<String,StringBuilder> notesInfo, String noteName){
        StringBuilder sb = notesInfo.get(noteName);
        if (sb == null) return;
        notesInfo.put(noteName,new StringBuilder(sb.toString().replaceAll(
                oldImagesBedPathReg, "vx_images/")));
    }
}
