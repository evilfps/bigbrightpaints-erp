package com.bigbrightpaints.erp;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.JwtProperties;
import com.bigbrightpaints.erp.core.config.LicensingProperties;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccountRepository;
import com.bigbrightpaints.erp.modules.auth.service.PasswordService;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import com.bigbrightpaints.erp.modules.company.domain.CompanyRepository;
import com.bigbrightpaints.erp.modules.rbac.domain.Role;
import com.bigbrightpaints.erp.modules.rbac.domain.RoleRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootApplication
@EnableRetry
@EnableScheduling
@EnableConfigurationProperties({JwtProperties.class, EmailProperties.class, LicensingProperties.class})
public class ErpDomainApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpDomainApplication.class, args);
    }

    @Bean
    @Profile("dev")
    @Transactional
    CommandLineRunner seedDemoUsers(UserAccountRepository userRepo, PasswordService passwordService, EmailService emailService, CompanyRepository companyRepo, PasswordEncoder encoder, RoleRepository roleRepo) {
        return args -> {
            Company bbp = companyRepo.findByCodeIgnoreCase("BBP").orElseGet(() -> {
                Company company = new Company();
                company.setCode("BBP");
                company.setName("Big Bright Paints Ltd");
                company.setTimezone("Asia/Kolkata");
                return companyRepo.save(company);
            });

            Role adminRole = roleRepo.findByName("ROLE_ADMIN").orElseGet(() -> {
                Role role = new Role();
                role.setName("ROLE_ADMIN");
                role.setDescription("Full system administrator");
                return roleRepo.save(role);
            });

            UserAccount mdAnas = userRepo.findByEmailIgnoreCase("mdanas7869292@gmail.com").orElseGet(() -> {
                UserAccount user = new UserAccount();
                user.setEmail("mdanas7869292@gmail.com");
                user.setDisplayName("Md Anas");
                String tempPass = "Admin@12345";
                user.setPasswordHash(encoder.encode(tempPass));
                emailService.sendUserCredentialsEmail(user.getEmail(), user.getDisplayName(), tempPass);
                System.out.println("✅ Seeded Md Anas & sent credentials email from bigbrightpaints@gmail.com");
                return user;
            });
            if (!mdAnas.getRoles().contains(adminRole)) mdAnas.getRoles().add(adminRole);
            if (!mdAnas.getCompanies().contains(bbp)) mdAnas.addCompany(bbp);
            userRepo.save(mdAnas);

            UserAccount ateeb = userRepo.findByEmailIgnoreCase("ateeb.warsi7869292@gmail.com").orElseGet(() -> {
                UserAccount user = new UserAccount();
                user.setEmail("ateeb.warsi7869292@gmail.com");
                user.setDisplayName("Ateeb Warsi");
                String tempPass = "Admin@12345";
                user.setPasswordHash(encoder.encode(tempPass));
                emailService.sendUserCredentialsEmail(user.getEmail(), user.getDisplayName(), tempPass);
                System.out.println("✅ Seeded Ateeb Warsi & sent credentials email from bigbrightpaints@gmail.com");
                return user;
            });
            if (!ateeb.getRoles().contains(adminRole)) ateeb.getRoles().add(adminRole);
            if (!ateeb.getCompanies().contains(bbp)) ateeb.addCompany(bbp);
            userRepo.save(ateeb);
        };
    }
}
