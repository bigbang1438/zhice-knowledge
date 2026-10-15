package com.zhice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  ApplicationRunner seed(
      JdbcTemplate jdbc,
      PasswordEncoder encoder,
      @Value("${zhice.demo-password:}") String password) {
    if (password.length() < 12)
      throw new IllegalStateException(
          "Set ZHICE_DEMO_PASSWORD to at least 12 characters before starting the local demo.");
    return args -> {
      for (String[] user :
          new String[][] {{"anna", "demo_anna", "演示用户 Anna"}, {"ben", "demo_ben", "演示用户 Ben"}}) {
        jdbc.update(
            "MERGE INTO app_users(id,username,display_name,password_hash) KEY(id) VALUES(?,?,?,?)",
            user[0],
            user[1],
            user[2],
            encoder.encode(password));
      }
      for (String[] space :
          new String[][] {{"team-guide", "团队手册", "anna"}, {"private-guide", "Ben 的独立资料库", "ben"}}) {
        jdbc.update(
            "INSERT INTO spaces(id,name,revision) SELECT ?,?,0 WHERE NOT EXISTS(SELECT 1 FROM"
                + " spaces WHERE id=?)",
            space[0],
            space[1],
            space[0]);
        jdbc.update(
            "MERGE INTO memberships(user_id,space_id) KEY(user_id,space_id) VALUES(?,?)",
            space[2],
            space[0]);
      }
    };
  }

  @Bean
  UserDetailsService users(JdbcTemplate jdbc) {
    return username ->
        jdbc
            .query(
                "SELECT username,password_hash FROM app_users WHERE username=?",
                (rs, n) ->
                    User.withUsername(rs.getString(1))
                        .password(rs.getString(2))
                        .roles("USER")
                        .build(),
                username)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/session",
                        "/api/login",
                        "/",
                        "/index.html",
                        "/assets/**",
                        "/favicon.ico",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> ApiErrors.write(r, 401, "unauthorized", "请先登录"))
                    .accessDeniedHandler(
                        (q, r, x) -> ApiErrors.write(r, 403, "forbidden", "权限不足或会话校验已失效，请刷新页面")))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/login")
                    .successHandler(
                        (q, r, a) -> {
                          r.setContentType("application/json");
                          r.getWriter().write("{\"authenticated\":true}");
                        })
                    .failureHandler(
                        (q, r, x) -> ApiErrors.write(r, 401, "invalid_credentials", "账号或密码不正确")))
        .logout(
            l ->
                l.logoutUrl("/api/logout")
                    .deleteCookies("JSESSIONID")
                    .logoutSuccessHandler(
                        (q, r, a) -> {
                          r.setContentType("application/json");
                          r.getWriter().write("{\"authenticated\":false}");
                        }))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        c ->
                            c.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'; img-src"
                                    + " 'self' data:; connect-src 'self'; object-src 'none';"
                                    + " base-uri 'self'; frame-ancestors 'none'; form-action"
                                    + " 'self'"))
                    .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER)))
        .requestCache(c -> c.disable());
    return http.build();
  }
}
