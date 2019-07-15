package SpringMySelf.v1.servcie;

import SpringMySelf.v1.annotation.MyService;

@MyService
public class DemoService {
    public String getName(String name){
        return name+name;
    }
}
