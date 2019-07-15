package SpringMySelf.v1.servlet;

import SpringMySelf.v1.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DispatcherServlet extends HttpServlet {
    private Properties contextConfig =new Properties();// 配置文件对象
    private List<String> classNames =new ArrayList<>();// 扫描的相关的类集合(全类名)
    private Map<String,Object> ioc =new HashMap<>();// ioc容器
    private Map<String,Method> handleMapping =new HashMap<>();// 存储url和方法形成映射关系
    private List<Handler> handlerList =new ArrayList<>();// 保存url和方法的映射对象
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        this.doPost(req,resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        doDispatcher(req,resp);
    }



    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        try {
            doDispatcher1(req,resp);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        System.out.println("config.getInitParameter(\"contextConfigurationLocation\")"+config.getInitParameter("contextConfigurationLocation"));

        //1 加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigurationLocation"));
        //2 扫描相关的全包名的类
        doScan(contextConfig.getProperty("scanPackage"));
        //3 将扫描到的类，创建其对象，放入ioc容器之中
        doInstance();
        //4 完成依赖注入DI
        doAutoWired();
        //5 初始化handleMapping形成映射
        doHandleMapping();
    }
    private void doDispatcher1(HttpServletRequest req, HttpServletResponse resp) throws IOException, InvocationTargetException, IllegalAccessException {
       Handler handler =getHandler(req);
       if (handler == null){
           resp.getWriter().write("404");
           return;
       }

       // 给方法的形参 配对相应的值
        Map<String,String[]> params =req.getParameterMap();
       Class<?>[] paramTypes =handler.method.getParameterTypes();
       Object[] paramValues =new Object[paramTypes.length];
       for (Map.Entry<String,String[]> entry:params.entrySet()){
           String paramValue =Arrays.toString(entry.getValue()).replaceAll("\\[|\\]","").replaceAll("\\s+","");
           if (!handler.paramIndexMapping.containsKey(entry.getKey())){continue;}
           int index =handler.paramIndexMapping.get(entry.getKey());// 获取形参的位置
           paramValues[index] =convert(paramTypes[index],paramValue);
       }
       if (handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())){
           int index =handler.paramIndexMapping.get(HttpServletRequest.class.getName());
           paramValues[index] =req;
       }
        if (handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())){
            int index =handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[index] =resp;
        }
        Object result =handler.method.invoke(handler.controller,paramValues);
        if (result == null || result instanceof  Void) {return;}
        resp.getWriter().write(result.toString());
    }
    public Object convert(Class<?> type,String value){
        if (Integer.class == type)
            return Integer.parseInt(value);
        return value;
    }

    private Handler getHandler(HttpServletRequest req) {
        String url =req.getRequestURI();
        String contextPath =req.getContextPath();
        url =url.replace(contextPath,"").replaceAll("/+","/");
        for (Handler handler:handlerList){
            Matcher matcher =handler.pattern.matcher(url);
            if (!matcher.matches()){continue;}
            return handler;
        }
        return null;
    }

    private void doDispatcher(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // 通过req获取请求的数据
        String url =req.getRequestURI();
        String contextPath =req.getContextPath();
        url =url.replace(contextPath,"").replaceAll("/+","/");
        if (!handleMapping.containsKey(url)){
            resp.getWriter().write("404");
            return;
        }
        Method method =handleMapping.get(url);
        if (method == null) {
            resp.getWriter().write("404");
            return;
        }
        /**
         * 没法控制参数的顺序等
         */
        // 获取req传来的参数
        Map<String,String[]> params =req.getParameterMap();
        // 获取形参列表
        Class<?>[] paramTypes =method.getParameterTypes();
        Object[] paramValues =new Object[paramTypes.length];// 形参的值
        for (int i=0;i<paramTypes.length;i++){// 遍历形参列表 获取形参的值
            Class<?> clazz =paramTypes[i];
            if (clazz == HttpServletRequest.class){
                paramValues[i] =req;
                continue;
            }else if (clazz == HttpServletResponse.class){
                paramValues[i] =resp;
                continue;
            }else if (clazz == String.class){
                MyRequestparam myParam = clazz.getAnnotation(MyRequestparam.class);
                if (params.containsKey(myParam)){
                    String value =Arrays.toString(params.get(myParam)).replaceAll("\\[|\\]","").replaceAll("\\s","");
                    paramValues[i] =value;
                }
            }
        }
    }
    private void doHandleMapping() {
        // 查找类的方法 进行方法映射
        if (ioc.isEmpty()){return;}
        for (Map.Entry<String,Object> entry:ioc.entrySet()){
            Class<?> clazz =entry.getValue().getClass();// 获取 类对象
            if (!clazz.isAnnotationPresent(MyController.class)){continue;}// 过滤
            // 保存写在类上的@RequestMapping("\base")
            String baseUrl ="";
            if (clazz.isAnnotationPresent(MyRequestMapping.class)){
                baseUrl =clazz.getAnnotation(MyRequestMapping.class).value();
            }
            // 写在方法上的@RequestMapping
            Method[] methods =clazz.getMethods();
            for (Method method:methods){
                if (!method.isAnnotationPresent(MyRequestMapping.class)){continue;}
                MyRequestMapping myRequestMapping =method.getAnnotation(MyRequestMapping.class);
                String urlName =myRequestMapping.value();
                String url =("/"+baseUrl+"/"+urlName).replaceAll("/+","/");
                //handleMapping.put(url,method);
                Pattern pattern =Pattern.compile(url);
                handlerList.add(new Handler(entry.getValue(),method,pattern));
            }
        }
    }

    private void doAutoWired() {
        // 查找类的属性 进行依赖注入
        if (ioc.isEmpty()) {return;}
        for (Map.Entry<String,Object> entry:ioc.entrySet()){
            Field[] fields =entry.getValue().getClass().getDeclaredFields();
            for (Field field:fields){
                if (!field.isAnnotationPresent(MyAutoWired.class)){continue;}
                MyAutoWired myAutoWired =field.getAnnotation(MyAutoWired.class);
                String className =myAutoWired.value();
                if ("".equals(className.trim())){
                    className =field.getName();
                }
                field.setAccessible(true);
                try {
                    // 给字段赋值
                    field.set(entry.getValue(),ioc.get(className));// ioc存放所有的类对象
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void doInstance() {
        // 将有注解的类 放入ioc容器
        if (classNames.isEmpty()){return;}
        try {
            for (String className:classNames){// 遍历扫描到的类
                Class<?> clazz =Class.forName(className);// 创建Class对象
                if (clazz.isAnnotationPresent(MyController.class)){// 根据类的注解 分别做处理
                    Object object =clazz.newInstance();
                    String classNameKey =Character.toLowerCase(clazz.getSimpleName().charAt(0))+clazz.getSimpleName().substring(1);
                    ioc.put(classNameKey,object);
                }else if (clazz.isAnnotationPresent(MyService.class)){
                    MyService myService =clazz.getAnnotation(MyService.class);
                    Object object =clazz.newInstance();
                    String classNameKey =myService.value();
                    if ("".endsWith(classNameKey.trim())){
                        classNameKey =Character.toLowerCase(clazz.getSimpleName().charAt(0))+clazz.getSimpleName().substring(1);
                    }
                    ioc.put(classNameKey,object);
                    for (Class<?> i:clazz.getInterfaces()){
                        if (ioc.containsKey(i.getName())){
                            throw new Exception("这个"+i.getName()+"已经存在");
                        }else {
                            ioc.put(i.getName(),object);
                        }
                    }
                }else {
                    continue;
                }

            }
        }catch (Exception e){
            e.printStackTrace();
        }

    }

    private void doScan(String scanPackage) {
        URL scanPackageUrl =this.getClass().getClassLoader().getResource("/"+scanPackage.replaceAll("\\.","/"));
        File classfile =new File(scanPackageUrl.getFile());
        for (File file:classfile.listFiles()){
            if (file.isDirectory()){
                doScan(scanPackage+"."+file.getName());
            }else {
                if (!file.getName().endsWith(".class")) { continue; }
                else {
                    String className =scanPackage+"."+file.getName().replace(".class","");
                    classNames.add(className);
                }
            }

        }
    }

    private void doLoadConfig(String contextConfigurationLocation) {
        InputStream inputStream =this.getClass().getClassLoader().getResourceAsStream(contextConfigurationLocation);
        try {
            contextConfig.load(inputStream);
        }catch (Exception e){
            if (inputStream!=null){
                try {
                    inputStream.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }
    private class Handler{
        Object controller;
        Method method;
        Pattern pattern;
        Map<String,Integer> paramIndexMapping;

        public Handler(Object controller, Method method, Pattern pattern) {
            this.controller = controller;
            this.method = method;
            this.pattern = pattern;
            paramIndexMapping =new HashMap<>();
            putParamIndexMapping();
        }
        // 获取方法中的形参类型及其位置
        public void putParamIndexMapping(){
            // 提取 有注解的参数
            Annotation[][] annotations =method.getParameterAnnotations();
            for (int i =0; i<annotations.length;i++){
                for (Annotation annotation:annotations[i]){
                    if (annotation instanceof MyRequestparam){
                        String paramName =((MyRequestparam)annotation).value();
                        if (!"".equals(paramName)){
                            paramIndexMapping.put(paramName,i);
                        }
                    }
                }
            }
            // 提取 request和response
            Class<?> [] paramTypes =method.getParameterTypes();// 形参列表
            for (int i=0;i<paramTypes.length;i++){
                Class<?> paramType =paramTypes[i];
                if (paramType == HttpServletRequest.class||
                paramType == HttpServletResponse.class){
                    paramIndexMapping.put(paramType.getName(),i);
                }
            }
        }

    }
}

