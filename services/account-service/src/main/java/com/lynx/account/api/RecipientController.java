package com.lynx.account.api;

import com.lynx.account.config.JwtAuthFilter;
import com.lynx.account.domain.SavedRecipient;
import com.lynx.account.dto.RecipientDtos.SaveRecipientRequest;
import com.lynx.account.dto.RecipientDtos.SavedRecipientView;
import com.lynx.account.service.RecipientService;
import com.lynx.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's own transfer address book — ordinary end-user auth only, exactly
 * like {@link AccountController}; every recipient here is scoped to the
 * caller's own {@code userId}, never visible to or editable by anyone else.
 */
@RestController
@RequestMapping("/v1/recipients")
public class RecipientController {

  private final RecipientService recipientService;

  public RecipientController(RecipientService recipientService) {
    this.recipientService = recipientService;
  }

  @GetMapping
  public List<SavedRecipientView> list(HttpServletRequest httpRequest) {
    return recipientService.list(userId(httpRequest)).stream().map(RecipientController::toView).toList();
  }

  @PostMapping
  public SavedRecipientView save(@RequestBody SaveRecipientRequest request, HttpServletRequest httpRequest) {
    SavedRecipient saved = recipientService.save(userId(httpRequest), request.label(), request.recipientUserId());
    return toView(saved);
  }

  @DeleteMapping("/{id}")
  public void remove(@PathVariable UUID id, HttpServletRequest httpRequest) {
    recipientService.remove(userId(httpRequest), id);
  }

  private static String userId(HttpServletRequest request) {
    UserContext userContext =
        (UserContext) request.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
    return userContext.userId();
  }

  private static SavedRecipientView toView(SavedRecipient recipient) {
    return new SavedRecipientView(
        recipient.getId(), recipient.getLabel(), recipient.getRecipientUserId(), recipient.getUpdatedAt());
  }
}
