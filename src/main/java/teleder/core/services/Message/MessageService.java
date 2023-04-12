package teleder.core.services.Message;

import org.modelmapper.ModelMapper;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import teleder.core.dtos.PayloadAction;
import teleder.core.dtos.PayloadMessage;
import teleder.core.dtos.SocketPayload;
import teleder.core.exceptions.NotFoundException;
import teleder.core.models.Conservation.Conservation;
import teleder.core.models.Group.Group;
import teleder.core.models.Message.Message;
import teleder.core.models.User.User;
import teleder.core.repositories.IConservationRepository;
import teleder.core.repositories.IGroupRepository;
import teleder.core.repositories.IMessageRepository;
import teleder.core.repositories.IUserRepository;
import teleder.core.services.Message.dtos.CreateMessageDto;
import teleder.core.services.Message.dtos.MessageDto;
import teleder.core.services.Message.dtos.UpdateMessageDto;
import teleder.core.utils.CONSTS;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class MessageService implements IMessageService {
    final
    SimpMessagingTemplate simpMessagingTemplate;
    final
    IMessageRepository messageRepository;
    final
    IUserRepository userRepository;
    final
    IConservationRepository conservationRepository;
    private final ModelMapper toDto;
    private final IGroupRepository iGroupRepository;

    public MessageService(SimpMessagingTemplate simpMessagingTemplate, IMessageRepository messageRepository, IUserRepository userRepository, IConservationRepository conservationRepository, ModelMapper toDto,
                          IGroupRepository iGroupRepository) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.conservationRepository = conservationRepository;
        this.toDto = toDto;
        this.iGroupRepository = iGroupRepository;
    }

    @Async
    @Override
    public CompletableFuture<Message> sendPrivateMessage(String contactId, PayloadMessage messagePayload) {
        String userId = ((UserDetails) (((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest().getAttribute("user"))).getUsername();
        // check conservation da tao hay chua neu chua tao thi tao moi
        User user = userRepository.findById(userId).orElse(null);
        User contact = userRepository.findById(contactId).orElse(null);
        Message message = new Message(messagePayload.getContent(), messagePayload.getCode(), messagePayload.getType(), user, contact, null, messagePayload.getFile());
        if (user == null || contact == null)
            throw new NotFoundException("Not found user");
        Conservation conservation = user.getConservations().stream()
                .filter(x -> x.getUser_1().getId().contains(contactId) || x.getUser_2().getId().contains(contactId))
                .findFirst().orElse(null);
        if (conservation == null) {
            conservation = new Conservation(user, message.getUser_receive(), null);
            conservation = conservationRepository.save(conservation);
            user.getConservations().add(conservation);
            contact.getConservations().add(conservation);
            user.setConservations(user.getConservations());
            contact.setConservations(contact.getConservations());
            userRepository.save(user);
            userRepository.save(contact);
        }
        // add tin nhan vao db
        message.setUser_send(user);
        message.setUser_receive(contact);
        message.setCode(conservation.getCode());
        message = messageRepository.save(message);
        conservation = conservationRepository.findByCode(message.getCode());
        conservation.setLastMessage(message);
        conservationRepository.save(conservation);
        simpMessagingTemplate.convertAndSend("/messages/user." + contactId, SocketPayload.create(message, CONSTS.MESSAGE_PRIVATE));
        return CompletableFuture.completedFuture(message);
    }

    @Async
    @Override
    public CompletableFuture<Void> sendAction(PayloadAction input) {
        String userId = ((UserDetails) (((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest().getAttribute("user"))).getUsername();
        // check conservation da tao hay chua neu chua tao thi tao moi
        User user = userRepository.findById(userId).orElse(null);
        User contact = userRepository.findById(input.getReceiverId()).orElse(null);
        if (user == null || contact == null)
            throw new NotFoundException("Not found user");
        if (input.getReceiverType() == CONSTS.MESSAGE_GROUP)
            simpMessagingTemplate.convertAndSend("/messages/group." + input.getReceiverId(), SocketPayload.create(input, input.getAction()));
        else
            simpMessagingTemplate.convertAndSend("/messages/user." + input.getReceiverId(), SocketPayload.create(input, input.getAction()));
        return CompletableFuture.completedFuture(null);
    }

    @Async
    @Override
    public CompletableFuture<Message> sendGroupMessage(String groupId, PayloadMessage messagePayload) {
        String userId = ((UserDetails) (((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest().getAttribute("user"))).getUsername();
        User user = userRepository.findById(userId).orElse(null);
        Group group = iGroupRepository.findById(groupId).orElse(null);
        Conservation conservation = user.getConservations().stream()
                .filter(x -> x.getGroup().getId().contains(groupId))
                .findFirst().orElse(null);
        if (user == null)
            throw new NotFoundException("Not found user");
        if (conservation == null)
            throw new NotFoundException("Not found Conservation");
        Message message = new Message(messagePayload.getCode(), messagePayload.getContent(), messagePayload.getType(), user, group, null, messagePayload.getFile());
        message.setUser_send(user);
        message.setCode(conservation.getCode());
        message.setGroup(conservation.getGroup());
        message.setTYPE(CONSTS.MESSAGE_GROUP);
        conservation = conservationRepository.findByCode(message.getCode());
        conservation.setLastMessage(message);
        conservationRepository.save(conservation);
        message = messageRepository.save(message);
        simpMessagingTemplate.convertAndSend("/messages/group." + groupId, SocketPayload.create(message, CONSTS.MESSAGE_GROUP));
        return CompletableFuture.completedFuture(message);
    }

    @Override
    public CompletableFuture<List<Message>> findMessagesWithPaginationAndSearch(long skip, int limit, String code, String content) {
        String userId = ((UserDetails) (((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest().getAttribute("user"))).getUsername();
        if (!userRepository.findById(userId).get().getConservations().stream().anyMatch(elem -> elem.getCode().contains(code)))
            throw new NotFoundException("Not Found Conservation!");
        List<Message> messages = messageRepository.findMessagesWithPaginationAndSearch(skip, limit, code, content);
        messages = messages.stream()
                .sorted(Comparator.comparing(Message::getCreateAt))
                .collect(Collectors.toList());
        return CompletableFuture.completedFuture(messages);
    }

    @Override
    public CompletableFuture<Long> countMessagesByCode(String code) {
        return CompletableFuture.supplyAsync(() -> messageRepository.countMessagesByCode(code).orElse(0L));
    }

    @Override
    public CompletableFuture<Message> markAsDelivered(String code) {
        Message message = messageRepository.findByCode(code).orElse(null);
        if (message == null)
            throw new NotFoundException("Not Found Message!");
        message.setDeliveredAt(new Date());
        message = messageRepository.save(message);
        return CompletableFuture.completedFuture(message);
    }

    @Override
    public CompletableFuture<Message> markAsRead(String code) {
        Message message = messageRepository.findByCode(code).orElse(null);
        if (message == null)
            throw new NotFoundException("Not Found Message!");
        message.setReadAt(new Date());
        message = messageRepository.save(message);
        return CompletableFuture.completedFuture(message);
    }

    /// Basic CRUD

    @Override
    @Async
    public CompletableFuture<MessageDto> create(CreateMessageDto input) {
        return null;
    }

    @Override
    @Async
    public CompletableFuture<MessageDto> getOne(String id) {
        return null;
    }

    @Override
    @Async
    public CompletableFuture<List<MessageDto>> getAll() {
        return null;
    }

    @Override
    @Async
    public CompletableFuture<MessageDto> update(String id, UpdateMessageDto input) {
        return null;
    }


    @Override
    @Async
    public CompletableFuture<Void> delete(String id) {
        return null;
    }
}
