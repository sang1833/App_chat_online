package teleder.core.models.Group;

import lombok.Data;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.DBRef;
import teleder.core.models.User.User;

import java.util.Date;

@Data
public class Member {
    Role role;
    Status status;
    @Indexed(unique = true)
    private String userId;
    @CreatedBy
    private Date createAt = new Date();
    @LastModifiedDate
    private Date updateAt = new Date();
    @DBRef
    private User addedBy;

    User user;
    public Member(String userId, User addedBy, Status status) {
        this.userId = userId;
        this.addedBy = addedBy;
        this.status = status;
    }
    public Member(User user, User addedBy, Status status) {
        this.user = user;
        this.addedBy = addedBy;
        this.status = status;
    }
    public enum Status {
        ACCEPT,
        WAITING,
        REQUEST
    }
}