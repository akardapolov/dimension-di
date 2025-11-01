package ru.dimension.di.beans;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class ConsumerBean {
  public final SingletonBean singletonBean;
  public final PrototypeBean firstBean;
  public final PrototypeBean secondBean;

  @Inject
  public ConsumerBean(SingletonBean singletonBean, PrototypeBean firstBean, PrototypeBean secondBean) {
    this.singletonBean = singletonBean;
    this.firstBean = firstBean;
    this.secondBean = secondBean;
  }
}
