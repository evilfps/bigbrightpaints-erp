package com.bigbrightpaints.erp.modules.sales.domain;

import com.bigbrightpaints.erp.core.util.CompanyTime;
import com.bigbrightpaints.erp.core.domain.VersionedEntity;
import com.bigbrightpaints.erp.modules.accounting.domain.Account;
import com.bigbrightpaints.erp.modules.accounting.domain.GstRegistrationType;
import com.bigbrightpaints.erp.modules.auth.domain.UserAccount;
import com.bigbrightpaints.erp.modules.company.domain.Company;
import jakarta.persistence.*;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.proxy.HibernateProxy;

@Entity
@Table(name = "dealers", uniqueConstraints = @UniqueConstraint(columnNames = {"company_id", "code"}))
public class Dealer extends VersionedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String code;

    @Column(name = "company_name")
    private String companyName;

    private String email;
    private String phone;
    private String address;

    @Column(name = "gst_number")
    private String gstNumber;

    @Column(name = "state_code")
    private String stateCode;

    @Column(name = "region")
    private String region;

    @Enumerated(EnumType.STRING)
    @Column(name = "gst_registration_type", nullable = false)
    private GstRegistrationType gstRegistrationType = GstRegistrationType.UNREGISTERED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_terms", nullable = false)
    private DealerPaymentTerms paymentTerms = DealerPaymentTerms.NET_30;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "credit_limit", nullable = false)
    @PositiveOrZero
    private BigDecimal creditLimit = BigDecimal.ZERO;

    @Column(name = "outstanding_balance", nullable = false)
    private BigDecimal outstandingBalance = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portal_user_id")
    private UserAccount portalUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receivable_account_id")
    private Account receivableAccount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (publicId == null) {
            publicId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = CompanyTime.now(company);
        }
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String gstNumber) { this.gstNumber = gstNumber; }
    public String getStateCode() { return stateCode; }
    public void setStateCode(String stateCode) { this.stateCode = stateCode; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public GstRegistrationType getGstRegistrationType() { return gstRegistrationType; }
    public void setGstRegistrationType(GstRegistrationType gstRegistrationType) {
        this.gstRegistrationType = gstRegistrationType == null ? GstRegistrationType.UNREGISTERED : gstRegistrationType;
    }
    public DealerPaymentTerms getPaymentTerms() { return paymentTerms; }
    public void setPaymentTerms(DealerPaymentTerms paymentTerms) {
        this.paymentTerms = paymentTerms == null ? DealerPaymentTerms.NET_30 : paymentTerms;
    }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = creditLimit; }
    public BigDecimal getOutstandingBalance() { return outstandingBalance; }
    public void setOutstandingBalance(BigDecimal outstandingBalance) { this.outstandingBalance = outstandingBalance; }
    public UserAccount getPortalUser() { return portalUser; }
    public void setPortalUser(UserAccount portalUser) { this.portalUser = portalUser; }
    public Account getReceivableAccount() { return receivableAccount; }
    public void setReceivableAccount(Account receivableAccount) { this.receivableAccount = receivableAccount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if (!Hibernate.getClass(this).equals(Hibernate.getClass(o))) return false;
        Dealer dealer = (Dealer) o;
        Long thisId = this instanceof HibernateProxy proxy
                ? (Long) proxy.getHibernateLazyInitializer().getIdentifier()
                : getId();
        Long otherId = dealer instanceof HibernateProxy proxy
                ? (Long) proxy.getHibernateLazyInitializer().getIdentifier()
                : dealer.getId();
        return thisId != null && Objects.equals(thisId, otherId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }
}
