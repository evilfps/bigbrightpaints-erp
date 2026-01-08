package com.bigbrightpaints.erp;

import com.bigbrightpaints.erp.core.config.EmailProperties;
import com.bigbrightpaints.erp.core.notification.EmailService;
import com.bigbrightpaints.erp.core.security.JwtProperties;
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

import java.security.SecureRandom;

@SpringBootApplication
@EnableRetry
@EnableScheduling
@EnableConfigurationProperties({JwtProperties.class, EmailProperties.class})
public class ErpDomainApplication {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String UPPERCASE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWERCASE_CHARS = "abcdefghjkmnpqrstuvwxyz";
    private static final String DIGIT_CHARS = "23456789";
    private static final String SPECIAL_CHARS = "!@#$%^&*";
    private static final String ALL_CHARS = UPPERCASE_CHARS + LOWERCASE_CHARS + DIGIT_CHARS + SPECIAL_CHARS;

    public static void main(String[] args) {
        SpringApplication.run(ErpDomainApplication.class, args);
    }

    /**
     * Generates a cryptographically secure temporary password.
     * Password meets policy requirements: 12+ chars, upper, lower, digit, special char.
     */
    private static String generateSecureTemporaryPassword() {
        StringBuilder password = new StringBuilder(14);
        // Ensure at least one of each required character type
        password.append(UPPERCASE_CHARS.charAt(SECURE_RANDOM.nextInt(UPPERCASE_CHARS.length())));
        password.append(LOWERCASE_CHARS.charAt(SECURE_RANDOM.nextInt(LOWERCASE_CHARS.length())));
        password.append(DIGIT_CHARS.charAt(SECURE_RANDOM.nextInt(DIGIT_CHARS.length())));
        password.append(SPECIAL_CHARS.charAt(SECURE_RANDOM.nextInt(SPECIAL_CHARS.length())));
        // Fill remaining characters randomly from all allowed characters
        for (int i = 4; i < 14; i++) {
            password.append(ALL_CHARS.charAt(SECURE_RANDOM.nextInt(ALL_CHARS.length())));
        }
        // Shuffle the password to avoid predictable patterns
        char[] chars = password.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char temp = chars[i];
            chars[i] = chars[j];
            chars[j] = temp;
        }
        return new String(chars);
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
                String tempPass = generateSecureTemporaryPassword();
                user.setPasswordHash(encoder.encode(tempPass));
                user.setMustChangePassword(true);
                emailService.sendUserCredentialsEmail(user.getEmail(), user.getDisplayName(), tempPass);
                System.out.println("✅ Seeded Md Anas with secure temp password & sent credentials email");
                return user;
            });
            if (!mdAnas.getRoles().contains(adminRole)) mdAnas.getRoles().add(adminRole);
            if (!mdAnas.getCompanies().contains(bbp)) mdAnas.addCompany(bbp);
            userRepo.save(mdAnas);

            UserAccount ateeb = userRepo.findByEmailIgnoreCase("ateeb.warsi7869292@gmail.com").orElseGet(() -> {
                UserAccount user = new UserAccount();
                user.setEmail("ateeb.warsi7869292@gmail.com");
                user.setDisplayName("Ateeb Warsi");
                String tempPass = generateSecureTemporaryPassword();
                user.setPasswordHash(encoder.encode(tempPass));
                user.setMustChangePassword(true);
                emailService.sendUserCredentialsEmail(user.getEmail(), user.getDisplayName(), tempPass);
                System.out.println("✅ Seeded Ateeb Warsi with secure temp password & sent credentials email");
                return user;
            });
            if (!ateeb.getRoles().contains(adminRole)) ateeb.getRoles().add(adminRole);
            if (!ateeb.getCompanies().contains(bbp)) ateeb.addCompany(bbp);
            userRepo.save(ateeb);
        };
    }
}