package SpringMySelf.v1.servlet;

import SpringMySelf.v1.annotation.*;
import SpringMySelf.v1.servcie.DemoService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@MyController
@MyRequestMapping("/demo")
public class DemoAction {
    @MyAutoWired
    public DemoService demoService;
    @MyRequestMapping("/query")
    public void query(HttpServletRequest req, HttpServletResponse resp,
                      @MyRequestparam("name") String name){
        String result = demoService.getName(name);
        try {
            resp.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
