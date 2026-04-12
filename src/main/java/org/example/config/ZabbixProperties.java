package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("zabbix")
public class ZabbixProperties {

    private List<HostConfig> hosts = new ArrayList<>();

    public List<HostConfig> getHosts() { return hosts; }
    public void setHosts(List<HostConfig> hosts) { this.hosts = hosts; }

    public static class HostConfig {
        private String name;
        private String url;
        private String user;
        private String password;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
